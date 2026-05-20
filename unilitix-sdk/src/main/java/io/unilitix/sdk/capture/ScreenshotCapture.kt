package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.core.Screenshot
import io.unilitix.sdk.util.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal class ScreenshotCapture(
    private val config: UnilitixConfig,
    private val onScreenshot: (Screenshot) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unilitix-screenshot").also { it.isDaemon = true }
    }

    @Volatile private var ordinal = 0
    private var currentActivity: Activity? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { capture(it) }
            handler.postDelayed(this, config.screenshotIntervalMs)
        }
    }

    fun resetOrdinal() {
        ordinal = 0
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        handler.postDelayed(captureRunnable, config.screenshotIntervalMs)
    }

    override fun onActivityPaused(activity: Activity) {
        handler.removeCallbacks(captureRunnable)
        currentActivity = null
    }

    private fun capture(activity: Activity) {
        if (ordinal >= config.maxScreenshotsPerSession) return

        val rootView = activity.window.decorView.rootView
        if (rootView.width == 0 || rootView.height == 0) return

        val bitmap = try {
            val bmp = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            rootView.draw(canvas)
            bmp
        } catch (e: Exception) {
            Logger.w("ScreenshotCapture: bitmap capture failed: ${e.message}")
            return
        }

        val currentOrdinal = ordinal++
        val screenName = activity.javaClass.simpleName
        val viewportW = rootView.width
        val viewportH = rootView.height

        // Collect rects on main thread — View access is only safe here
        val maskRects = if (config.maskInputsInScreenshots) {
            collectMaskRects(rootView)
        } else emptyList()

        captureExecutor.execute {
            try {
                val masked = if (maskRects.isNotEmpty()) {
                    applyMaskRects(bitmap, maskRects)
                } else bitmap

                val resized = resizeToMaxWidth(masked, config.screenshotMaxWidth)
                val webpBytes = encodeWebP(resized, config.screenshotQuality)

                onScreenshot(
                    Screenshot(
                        ordinal = currentOrdinal,
                        capturedAt = System.currentTimeMillis(),
                        screenName = screenName,
                        viewportWidth = viewportW,
                        viewportHeight = viewportH,
                        imageBytes = webpBytes,
                    )
                )
            } catch (e: Exception) {
                Logger.w("ScreenshotCapture: processing failed: ${e.message}")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    // Returns Rect coordinates relative to rootView — safe to call on main thread only
    private fun collectMaskRects(rootView: View): List<Rect> {
        val rootLoc = IntArray(2)
        rootView.getLocationInWindow(rootLoc)

        val rects = mutableListOf<Rect>()
        walkViews(rootView) { view ->
            val shouldMask = when {
                view is EditText -> {
                    val it = view.inputType
                    val isPassword =
                        (it and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                        ((it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                         (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                        (it and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                        (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    isPassword || config.maskInputs || config.maskedViewIds.contains(view.id)
                }
                config.maskedViewIds.contains(view.id) -> true
                else -> false
            }
            if (shouldMask && view.width > 0 && view.height > 0) {
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                rects.add(
                    Rect(
                        loc[0] - rootLoc[0],
                        loc[1] - rootLoc[1],
                        loc[0] - rootLoc[0] + view.width,
                        loc[1] - rootLoc[1] + view.height,
                    )
                )
            }
        }
        return rects
    }

    private fun applyMaskRects(bitmap: Bitmap, rects: List<Rect>): Bitmap {
        val masked = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(masked)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (rect in rects) canvas.drawRect(rect, paint)
        return masked
    }

    private fun walkViews(view: View, callback: (View) -> Unit) {
        callback(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walkViews(view.getChildAt(i), callback)
        }
    }

    private fun resizeToMaxWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    private fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
        }
        return stream.toByteArray()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
