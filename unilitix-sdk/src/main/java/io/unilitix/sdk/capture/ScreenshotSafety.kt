package io.unilitix.sdk.capture

/**
 * The size a screenshot is drawn at (1.10.2): the window scaled down to
 * `screenshotMaxWidth`, keeping its aspect ratio. The bitmap is allocated at
 * this size and the view hierarchy drawn into it through a scaled canvas,
 * instead of a full-resolution bitmap, a masked copy and a scaled copy (three
 * full frames, ~30 MB on a 1440p phone, per capture).
 */
internal object ScreenshotSize {

    data class Target(val width: Int, val height: Int, val scale: Float)

    /** Null when the window has no area. Never larger than the source. */
    fun target(srcWidth: Int, srcHeight: Int, maxWidth: Int): Target? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val limit = maxWidth.coerceAtLeast(1)
        if (srcWidth <= limit) return Target(srcWidth, srcHeight, 1f)
        val scale = limit.toFloat() / srcWidth
        val height = (srcHeight * scale).toInt().coerceAtLeast(1)
        return Target(limit, height, scale)
    }
}

/**
 * Turns screenshot capture off for the rest of the process after an
 * OutOfMemoryError (1.10.2): when the heap is that tight, session replay is
 * the first thing to give up, not the host app. Other failures drop one frame.
 */
internal class CaptureFailureLatch {
    @Volatile var disabled: Boolean = false
        private set

    /** Records a capture failure; true when capture is now off. */
    fun record(t: Throwable): Boolean {
        if (t is OutOfMemoryError) disabled = true
        return disabled
    }
}
