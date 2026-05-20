package io.unilitix.sdk.capture

import kotlin.math.abs

internal data class TapRecord(val x: Float, val y: Float, val screen: String, val timestampMs: Long)

internal class RageTapDetector(
    private val onRageTap: (screen: String, x: Float, y: Float) -> Unit
) {
    private val recentTaps = mutableListOf<TapRecord>()

    fun recordTap(x: Float, y: Float, screen: String): Boolean {
        val now = System.currentTimeMillis()

        recentTaps.removeAll { now - it.timestampMs > WINDOW_MS }
        recentTaps.add(TapRecord(x, y, screen, now))

        val clusterTaps = recentTaps.filter { tap ->
            tap.screen == screen && abs(tap.x - x) <= RADIUS_PX && abs(tap.y - y) <= RADIUS_PX
        }

        if (clusterTaps.size >= MIN_TAPS) {
            val cx = clusterTaps.map { it.x }.average().toFloat()
            val cy = clusterTaps.map { it.y }.average().toFloat()
            recentTaps.removeAll(clusterTaps.toSet())
            onRageTap(screen, cx, cy)
            return true
        }

        return false
    }

    companion object {
        private const val WINDOW_MS = 1_000L
        private const val RADIUS_PX = 100f
        private const val MIN_TAPS = 3
    }
}
