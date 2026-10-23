package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import io.unilitix.sdk.Unilitix
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.UnilitixPrivate
import io.unilitix.sdk.core.Screenshot
import io.unilitix.sdk.util.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal class ScreenshotCapture(
    private val config: UnilitixConfig,
    /** False while consent is denied: nothing is captured. */
    private val isEnabled: () -> Boolean = { true },
    /** Current session id; a new id resets the scheduler (first frame, budget). */
    private val sessionId: () -> String? = { null },
    private val onScreenshot: (Screenshot) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unilitix-screenshot").also { it.isDaemon = true }
    }

    @Volatile private var ordinal = 0
    private var currentActivity: Activity? = null

    private val maskPolicy = MaskPolicy(
        maskInputs              = config.maskInputs,
        maskInputsInScreenshots = config.maskInputsInScreenshots,
        maskAllText             = config.maskAllText,
        maskImages              = config.maskImages,
    )

    /** Change + interval, per-minute budget and heartbeat. Main thread only. */
    private val scheduler = ReplayScheduler(
        intervalMs   = config.screenshotIntervalMs,
        heartbeatMs  = config.screenshotHeartbeatMs,
        maxPerMinute = config.maxScreenshotsPerMinute,
        clock        = { SystemClock.uptimeMillis() },
    )

    // How often the scheduler is asked. Capture itself happens only when it
    // says so; the check is cheap.
    private val tickMs = config.screenshotIntervalMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS)

    // Any frame the window draws marks the screen dirty. Our own software
    // draw into a bitmap does not go through ViewRootImpl, so it does not.
    private val drawListener = ViewTreeObserver.OnDrawListener { scheduler.markDirty() }
    private var observedView: View? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { capture(it) }
            handler.postDelayed(this, tickMs)
        }
    }

    fun resetOrdinal() {
        ordinal = 0
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        scheduler.markDirty() // a new screen
        observeDraws(activity.window.decorView)
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, tickMs)
    }

    override fun onActivityPaused(activity: Activity) {
        handler.removeCallbacks(captureRunnable)
        stopObservingDraws()
        currentActivity = null
    }

    private fun observeDraws(view: View) {
        stopObservingDraws()
        try {
            val vto = view.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnDrawListener(drawListener)
                observedView = view
            }
        } catch (e: Exception) {
            Logger.d("ScreenshotCapture: cannot observe draws: ${e.message}")
        }
    }

    private fun stopObservingDraws() {
        val view = observedView ?: return
        observedView = null
        try {
            val vto = view.viewTreeObserver
            if (vto.isAlive) vto.removeOnDrawListener(drawListener)
        } catch (_: Exception) {
            // Removing during a draw pass throws; the observer dies with the window anyway.
        }
    }

    private fun capture(activity: Activity) {
        if (!isEnabled()) return
        if (ordinal >= config.maxScreenshotsPerSession) return

        val rootView = activity.window.decorView.rootView
        if (rootView.width == 0 || rootView.height == 0) return

        // Asked last: a yes spends one unit of the per-minute budget.
        if (!scheduler.shouldCapture(sessionId() ?: "")) return

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

        // Full physical display size — rootView excludes nav bar on non-edge-to-edge builds.
        val viewportW: Int
        val viewportH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            viewportW = bounds.width()
            viewportH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(dm)
            viewportW = dm.widthPixels
            viewportH = dm.heightPixels
        }

        // Collect rects on main thread — View access is only safe here.
        // Private views and maskedViewIds are always masked; text, inputs and
        // images follow maskAllText / maskInputs(InScreenshots) / maskImages.
        val maskRects = try {
            collectMaskRects(rootView)
        } catch (e: Exception) {
            // Never upload a frame whose masking failed.
            Logger.w("ScreenshotCapture: masking failed, frame dropped: ${e.message}")
            bitmap.recycle()
            return
        }

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
        return ReplayMasking.screenshotRects(ViewNode(rootView, rootLoc), maskPolicy).map {
            Rect(it.left, it.top, it.right, it.bottom)
        }
    }

    /** Adapts a View to the masking rules in [ReplayMasking]. Main thread only. */
    private inner class ViewNode(private val view: View, private val rootLoc: IntArray) : MaskableNode {
        override val kind: NodeKind
            get() = when {
                view is EditText -> NodeKind.INPUT
                view is TextView -> NodeKind.TEXT
                view is ImageView -> if (view.drawable != null) NodeKind.IMAGE else NodeKind.OTHER
                view !is ViewGroup && view.background is BitmapDrawable -> NodeKind.IMAGE
                else -> NodeKind.OTHER
            }
        override val isVisible: Boolean get() = view.visibility == View.VISIBLE
        override val isPrivate: Boolean get() = view.javaClass.isAnnotationPresent(UnilitixPrivate::class.java)
        override val isMaskedById: Boolean get() = view.id != View.NO_ID && config.maskedViewIds.contains(view.id)
        override val isUnmasked: Boolean get() = Unilitix.isUnmasked(view)
        override val isPassword: Boolean
            get() {
                val it = (view as? EditText)?.inputType ?: return false
                return (it and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    ((it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                     (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                    (it and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                    (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
        // Visible part only: a label scrolled half out of a list is masked
        // where it shows, not over the header above the list.
        override val rect: MaskRect?
            get() {
                if (view.width <= 0 || view.height <= 0) return null
                val r = Rect()
                if (!view.getGlobalVisibleRect(r)) return null
                return MaskRect(r.left - rootLoc[0], r.top - rootLoc[1], r.right - rootLoc[0], r.bottom - rootLoc[1])
            }
        override val children: List<MaskableNode>
            get() {
                val group = view as? ViewGroup ?: return emptyList()
                return List(group.childCount) { ViewNode(group.getChildAt(it), rootLoc) }
            }
    }

    private fun applyMaskRects(bitmap: Bitmap, rects: List<Rect>): Bitmap {
        val masked = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(masked)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (rect in rects) canvas.drawRect(rect, paint)
        return masked
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

    companion object {
        private const val MIN_TICK_MS = 250L
        private const val MAX_TICK_MS = 1_000L
    }
}
