package io.unilitix.sdk.storage

import androidx.room.ColumnInfo

/** Id and encoded size of one queued screenshot (projection of `pending_screenshots`). */
internal data class ScreenshotSize(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "bytes") val bytes: Long,
)

/**
 * On-device bound for the screenshot queue. Screenshots can wait for Wi-Fi
 * (`uploadScreenshotsOnWifiOnly`) across many sessions, so the queue is capped
 * by count and by total bytes; the oldest go first.
 */
internal object ScreenshotQueueCap {
    /** Total encoded bytes kept on the device (1500 frames at ~15 KB fit comfortably). */
    const val MAX_BYTES: Long = 40L * 1024 * 1024

    /**
     * Ids to delete so that at most [maxCount] screenshots and [maxBytes]
     * bytes remain. [newestFirst] is the whole queue, newest first.
     */
    fun idsToEvict(newestFirst: List<ScreenshotSize>, maxCount: Int, maxBytes: Long = MAX_BYTES): List<Long> {
        val evict = mutableListOf<Long>()
        var kept = 0
        var bytes = 0L
        var full = false
        for (s in newestFirst) {
            // Once one frame does not fit, everything older goes too: oldest first.
            if (!full && kept < maxCount && bytes + s.bytes <= maxBytes) {
                kept++
                bytes += s.bytes
            } else {
                full = true
                evict.add(s.id)
            }
        }
        return evict
    }
}
