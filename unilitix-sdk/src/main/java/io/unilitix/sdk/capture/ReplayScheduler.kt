package io.unilitix.sdk.capture

/**
 * Decides when the next screenshot is taken. Same rules as the iOS SDK's
 * `ReplayScheduler` and the Flutter SDK's `ScreenshotCapture`:
 *
 * - A frame is wanted when the screen was marked dirty (it redrew, or a new
 *   screen resumed) and at least [intervalMs] passed since the last frame, or
 *   when [heartbeatMs] passed on a static screen.
 * - A rolling one-minute budget ([maxPerMinute]) stops a screen that redraws
 *   continuously (an animation, a blinking cursor) from flooding the session.
 * - The first frame of every session is wanted as soon as it is checked.
 *
 * Pure logic with an injected clock, so it is unit-tested on the JVM. Not
 * thread-safe; [ScreenshotCapture] uses it on the main thread only.
 *
 * @param heartbeatMs `screenshotHeartbeatMs`; 0 or less turns the heartbeat
 *   off. Never shorter than [intervalMs].
 * @param clock monotonic milliseconds (`SystemClock.uptimeMillis` on device).
 */
internal class ReplayScheduler(
    intervalMs: Long,
    heartbeatMs: Long,
    maxPerMinute: Int,
    private val clock: () -> Long,
) {
    val intervalMs: Long = intervalMs.coerceAtLeast(0L)

    /** [Long.MAX_VALUE] when the heartbeat is off. */
    val heartbeatMs: Long =
        if (heartbeatMs > 0) heartbeatMs.coerceAtLeast(this.intervalMs) else Long.MAX_VALUE

    val maxPerMinute: Int = maxPerMinute.coerceAtLeast(0)

    var dirty: Boolean = true
        private set

    private var lastCapture: Long? = null
    private val recent = ArrayDeque<Long>()
    private var session: String? = null

    /** The screen changed; the next check after [intervalMs] captures. */
    fun markDirty() {
        dirty = true
    }

    /**
     * True when a frame should be taken now for [sessionId]. A true answer
     * spends one unit of the per-minute budget and clears the dirty flag, so
     * check every other precondition (consent, session cap, view laid out)
     * first.
     */
    fun shouldCapture(sessionId: String): Boolean {
        val now = clock()
        if (sessionId != session) {
            session = sessionId
            dirty = true
            lastCapture = null
            recent.clear()
        }
        val since = lastCapture?.let { now - it } ?: Long.MAX_VALUE
        val wanted = (dirty && since >= intervalMs) || since >= heartbeatMs
        if (!wanted) return false
        while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MS) recent.removeFirst()
        if (recent.size >= maxPerMinute) return false
        recent.addLast(now)
        dirty = false
        lastCapture = now
        return true
    }

    companion object {
        /** Length of the rolling budget window. */
        const val WINDOW_MS = 60_000L
    }
}
