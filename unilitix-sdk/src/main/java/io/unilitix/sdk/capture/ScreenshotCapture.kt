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
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import io.unilitix.sdk.Unilitix
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.core.Screenshot
import io.unilitix.sdk.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Screenshots of the resumed activity's window for session replay.
 *
 * 1.11.0: on API 26+ the frame is read with [PixelCopy] straight into a
 * bitmap at the upload size (`screenshotMaxWidth`), off the main thread: the
 * main thread only computes the mask rectangles and starts the copy, and the
 * GPU-rendered frame (including hardware bitmaps, which a software draw
 * cannot read) is what the user saw. Below API 26, or when PixelCopy fails
 * repeatedly, the view tree is drawn into a bitmap at the upload size through
 * a scaled canvas. Never a full-resolution ARGB bitmap. Masks are painted
 * before the frame is encoded; if anything fails the frame is dropped
 * (every Throwable is caught; an OutOfMemoryError turns capture off for the
 * process).
 *
 * Nothing is captured while consent is denied, while the session is not
 * sampled for replay, while a wrapper SDK supplies frames
 * ([ReplayState.externalCapture]) or on a FLAG_SECURE window.
 */
internal class ScreenshotCapture(
    private val config: UnilitixConfig,
    /** Consent allows capture and the current session is sampled for replay. */
    private val isEnabled: () -> Boolean = { true },
    /** Current session id; a new id resets the scheduler (first frame, budget). */
    private val sessionId: () -> String? = { null },
    private val replayState: ReplayState,
    private val onScreenshot: (sessionId: String, Screenshot) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())

    /** PixelCopy results and encoding happen here, never on the main thread. */
    private val worker: Handler by lazy {
        val t = HandlerThread("unilitix-screenshot")
        t.isDaemon = true
        t.start()
        Handler(t.looper)
    }

    private var currentActivity: Activity? = null

    /** Consecutive PixelCopy failures; after [MAX_PIXEL_COPY_FAILURES] the software draw is used. */
    @Volatile private var pixelCopyFailures = 0

    /** True while a frame is being copied or encoded: at most one in flight. */
    @Volatile private var busy = false

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

    private val tickMs = config.screenshotIntervalMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS)

    // Any frame the window draws marks the screen dirty.
    private val drawListener = ViewTreeObserver.OnDrawListener { scheduler.markDirty() }
    private var observedView: View? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (failureLatch.disabled) return // off for this process: stop ticking
            try {
                currentActivity?.let { capture(it) }
            } catch (t: Throwable) {
                // Runs on the host's main looper: nothing may escape.
                onCaptureFailure("capture", t)
                if (failureLatch.disabled) return
            }
            handler.postDelayed(this, tickMs)
        }
    }

    private fun onCaptureFailure(stage: String, t: Throwable) {
        if (failureLatch.record(t)) {
            Logger.w("ScreenshotCapture: out of memory during $stage; screenshots disabled for this process")
        } else {
            Logger.d("ScreenshotCapture: $stage failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (failureLatch.disabled) return
        try {
            currentActivity = activity
            scheduler.markDirty() // a new screen
            observeDraws(activity.window.decorView)
            handler.removeCallbacks(captureRunnable)
            handler.postDelayed(captureRunnable, tickMs)
        } catch (t: Throwable) {
            Logger.d("ScreenshotCapture: cannot attach to ${activity.javaClass.simpleName}: ${t.message}")
        }
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

    /** Main thread. */
    private fun capture(activity: Activity) {
        if (busy) return
        if (replayState.externalCapture) return
        if (!isEnabled()) return
        val session = sessionId() ?: return
        val window = activity.window ?: return

        // FLAG_SECURE marks a screen the app keeps out of screenshots (banking,
        // payments): replay honours it.
        if (ReplayMasking.isSecureWindow(window.attributes.flags)) return

        val rootView = window.decorView.rootView
        // A layout or draw is pending: the rectangles would not match the frame.
        if (rootView.isLayoutRequested) return
        val size = ScreenshotSize.target(rootView.width, rootView.height, config.screenshotMaxWidth) ?: return

        // Asked last: a yes spends one unit of the per-minute budget.
        if (!scheduler.shouldCapture(session)) return
        val ordinal = replayState.nextScreenshotOrdinal(session) ?: return

        PrivacyTags.scanActivity(activity)

        // The window in dp: the coordinate space taps are reported in.
        val windowPx = WindowGeometry.windowPixels(activity)
        val density = WindowGeometry.density(activity)
        val viewportW = Dp.of(windowPx.right - windowPx.left, density)
        val viewportH = Dp.of(windowPx.bottom - windowPx.top, density)
        val screenName = activity.javaClass.simpleName
        // In root-view pixels; painted after scaling. Computed here, on the
        // main thread: if masking cannot be computed the frame is not taken.
        val maskRects = collectMaskRects(rootView)
        val meta = FrameMeta(session, ordinal, screenName, viewportW, viewportH, size.scale, maskRects)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pixelCopyFailures < MAX_PIXEL_COPY_FAILURES &&
            rootView.isHardwareAccelerated
        ) {
            copyPixels(window, size, meta)
        } else {
            drawSoftware(rootView, size, meta)
        }
    }

    private class FrameMeta(
        val sessionId: String,
        val ordinal: Int,
        val screenName: String,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val scale: Float,
        val masks: List<Rect>,
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private fun copyPixels(window: Window, size: ScreenshotSize.Target, meta: FrameMeta) {
        val bitmap = try {
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            onCaptureFailure("allocation", t)
            return
        }
        busy = true
        try {
            // PixelCopy scales the window surface into the target-size bitmap.
            PixelCopy.request(window, bitmap, { result ->
                // Worker thread.
                if (result == PixelCopy.SUCCESS) {
                    pixelCopyFailures = 0
                    finish(bitmap, meta)
                } else {
                    pixelCopyFailures++
                    bitmap.recycle()
                    busy = false
                    Logger.d("ScreenshotCapture: PixelCopy result $result")
                }
            }, worker)
        } catch (t: Throwable) {
            // IllegalArgumentException: the window has no surface yet.
            bitmap.recycle()
            busy = false
            pixelCopyFailures++
            onCaptureFailure("pixel copy", t)
        }
    }

    /** API < 26 (or PixelCopy unavailable): the view tree drawn at the target scale, main thread. */
    private fun drawSoftware(rootView: View, size: ScreenshotSize.Target, meta: FrameMeta) {
        var bitmap: Bitmap? = null
        try {
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            val canvas = Canvas(bmp)
            if (size.scale < 1f) canvas.scale(size.scale, size.scale)
            // Throws IllegalArgumentException on hardware bitmaps (API 26+): caught, frame dropped.
            rootView.draw(canvas)
        } catch (t: Throwable) {
            bitmap?.recycle()
            onCaptureFailure("draw", t)
            return
        }
        val frame = bitmap ?: return
        busy = true
        try {
            worker.post { finish(frame, meta) }
        } catch (t: Throwable) {
            frame.recycle()
            busy = false
            onCaptureFailure("hand-off", t)
        }
    }

    /** Worker thread: paint masks, encode, hand off. */
    private fun finish(bitmap: Bitmap, meta: FrameMeta) {
        try {
            if (failureLatch.disabled) return
            if (meta.masks.isNotEmpty()) {
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                val s = meta.scale
                for (r in meta.masks) {
                    // Rounded outwards so a scaled mask never leaves a sliver.
                    canvas.drawRect(
                        Math.floor((r.left * s).toDouble()).toFloat(),
                        Math.floor((r.top * s).toDouble()).toFloat(),
                        Math.ceil((r.right * s).toDouble()).toFloat(),
                        Math.ceil((r.bottom * s).toDouble()).toFloat(),
                        paint,
                    )
                }
            }
            val bytes = encodeWebP(bitmap, config.screenshotQuality)
            onScreenshot(
                meta.sessionId,
                Screenshot(
                    ordinal = meta.ordinal,
                    capturedAt = System.currentTimeMillis(),
                    screenName = meta.screenName,
                    viewportWidth = meta.viewportWidth,
                    viewportHeight = meta.viewportHeight,
                    imageBytes = bytes,
                )
            )
        } catch (t: Throwable) {
            onCaptureFailure("encoding", t)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            busy = false
        }
    }

    // Rect coordinates relative to rootView — main thread only.
    private fun collectMaskRects(rootView: View): List<Rect> {
        val rootLoc = IntArray(2)
        rootView.getLocationInWindow(rootLoc)
        return ReplayMasking.screenshotRects(ViewNode(rootView, rootLoc), maskPolicy).map {
            Rect(it.left, it.top, it.right, it.bottom)
        }
    }

    /**
     * Adapts a View to the masking rules in [ReplayMasking]. Main thread only.
     * A Jetpack Compose host view has its semantics nodes as extra children
     * ([ComposeMaskNode]); one whose semantics cannot be read in full is
     * [isUnreadable] and masked whole.
     */
    private inner class ViewNode(private val view: View, private val rootLoc: IntArray) : MaskableNode {
        private val compose: ComposeTree? by lazy(LazyThreadSafetyMode.NONE) {
            if (ComposeHost.isHost(view)) ComposeHost.read(view) else null
        }
        // A WebView's page cannot be walked for its text and inputs: masked whole.
        override val isUnreadable: Boolean
            get() = view is android.webkit.WebView || (compose?.unreadableForScreenshot ?: false)
        override val kind: NodeKind
            get() = when {
                view is EditText -> NodeKind.INPUT
                view is TextView -> NodeKind.TEXT
                view is ImageView -> if (view.drawable != null) NodeKind.IMAGE else NodeKind.OTHER
                view !is ViewGroup && view.background is BitmapDrawable -> NodeKind.IMAGE
                else -> NodeKind.OTHER
            }
        override val isVisible: Boolean get() = view.visibility == View.VISIBLE
        override val isPrivate: Boolean get() = PrivacyTags.isPrivate(view)
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
        // Visible part only.
        override val rect: MaskRect?
            get() {
                if (view.width <= 0 || view.height <= 0) return null
                val r = Rect()
                if (!view.getGlobalVisibleRect(r)) return null
                return MaskRect(r.left - rootLoc[0], r.top - rootLoc[1], r.right - rootLoc[0], r.bottom - rootLoc[1])
            }
        override val children: List<MaskableNode>
            get() {
                val group = view as? ViewGroup
                val views: List<MaskableNode> =
                    if (group == null) emptyList() else List(group.childCount) { ViewNode(group.getChildAt(it), rootLoc) }
                val root = compose?.root ?: return views
                val clip = rect ?: return views
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                return listOf<MaskableNode>(ComposeMaskNode(root, loc[0] - rootLoc[0], loc[1] - rootLoc[1], clip)) + views
            }
    }

    private fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream(16 * 1024)
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

    /** One capture attempt now, as the tick would make it (instrumentation benchmarks). Main thread. */
    internal fun captureForTest(activity: Activity) = capture(activity)

    /** Stops ticking (SDK shutdown). */
    fun stop() {
        handler.removeCallbacks(captureRunnable)
        stopObservingDraws()
        currentActivity = null
    }

    companion object {
        private const val MIN_TICK_MS = 250L
        private const val MAX_TICK_MS = 1_000L
        private const val MAX_PIXEL_COPY_FAILURES = 3

        /** Process-wide: after an OutOfMemoryError no capture instance takes screenshots again. */
        internal val failureLatch = CaptureFailureLatch()
    }
}
