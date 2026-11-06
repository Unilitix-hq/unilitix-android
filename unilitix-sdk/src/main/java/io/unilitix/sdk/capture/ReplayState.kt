package io.unilitix.sdk.capture

/**
 * Replay bookkeeping shared by the SDK's own capture and frames or snapshots
 * supplied by a wrapper SDK (Flutter, React Native): per-session ordinals and
 * caps, the per-minute budget for supplied frames, and whether the wrapper
 * has taken over capture ([externalCapture]). Thread-safe.
 */
internal class ReplayState(
    private val maxScreenshotsPerSession: Int,
    private val maxSnapshotsPerSession: Int,
    private val maxScreenshotsPerMinute: Int,
) {
    /** True while a wrapper supplies frames: the view-based capture stands down. */
    @Volatile var externalCapture: Boolean = false

    private var sessionId: String? = null
    private var screenshotOrdinal = 0
    private var snapshotOrdinal = 0
    private val minute = ArrayDeque<Long>()

    private fun roll(session: String) {
        if (session == sessionId) return
        sessionId = session
        screenshotOrdinal = 0
        snapshotOrdinal = 0
        minute.clear()
    }

    /** The next screenshot ordinal of [session], or null once its cap is reached. */
    @Synchronized
    fun nextScreenshotOrdinal(session: String): Int? {
        roll(session)
        if (screenshotOrdinal >= maxScreenshotsPerSession) return null
        return screenshotOrdinal++
    }

    /** The next snapshot ordinal of [session], or null once its cap is reached. */
    @Synchronized
    fun nextSnapshotOrdinal(session: String): Int? {
        roll(session)
        if (snapshotOrdinal >= maxSnapshotsPerSession) return null
        return snapshotOrdinal++
    }

    /** The rolling one-minute budget (`maxScreenshotsPerMinute`) for a supplied frame at [nowMs] (monotonic). */
    @Synchronized
    fun admitFrame(session: String, nowMs: Long): Boolean {
        roll(session)
        while (minute.isNotEmpty() && nowMs - minute.first() >= 60_000L) minute.removeFirst()
        if (minute.size >= maxScreenshotsPerMinute) return false
        minute.addLast(nowMs)
        return true
    }
}
