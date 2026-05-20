package io.unilitix.sdk.capture

import android.view.Choreographer
import io.unilitix.sdk.util.Logger
import java.io.File
import kotlin.math.roundToInt

internal class PerformanceMonitor {

    @Volatile var memoryUsageMb: Float = 0f
        private set

    @Volatile var cpuUsagePct: Float = 0f
        private set

    @Volatile var frameDropCount: Int = 0
        private set

    private var lastFrameTimeNs: Long = 0L
    private var prevCpuTotal: Long = 0L
    private var prevCpuWork: Long = 0L
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNs > 0) {
                val deltaMs = (frameTimeNanos - lastFrameTimeNs) / 1_000_000L
                if (deltaMs > 32) {
                    frameDropCount++
                    Logger.d("PerformanceMonitor: frame drop detected (${deltaMs}ms)")
                }
            }
            lastFrameTimeNs = frameTimeNanos
            updateMemory()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        isRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        Logger.d("PerformanceMonitor: started")
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun sampleCpu() {
        try {
            val statLine = File("/proc/self/stat").readText().trim()
            val parts = statLine.split(" ")
            if (parts.size >= 17) {
                val utime = parts[13].toLong()
                val stime = parts[14].toLong()
                val cutime = parts[15].toLong()
                val cstime = parts[16].toLong()
                val work = utime + stime + cutime + cstime

                val uptimeLine = File("/proc/uptime").readText().trim()
                val total = (uptimeLine.split(" ")[0].toDouble() * 100).toLong()

                if (prevCpuTotal > 0) {
                    val deltaWork = work - prevCpuWork
                    val deltaTotal = total - prevCpuTotal
                    cpuUsagePct = if (deltaTotal > 0) (deltaWork.toFloat() / deltaTotal * 100f) else 0f
                }

                prevCpuWork = work
                prevCpuTotal = total
            }
        } catch (e: Exception) {
            Logger.d("PerformanceMonitor: CPU sampling not available")
        }
    }

    fun resetFrameDrops() {
        frameDropCount = 0
    }

    private fun updateMemory() {
        val rt = Runtime.getRuntime()
        val usedBytes = rt.totalMemory() - rt.freeMemory()
        memoryUsageMb = (usedBytes.toFloat() / (1024 * 1024) * 10).roundToInt() / 10f
    }
}
