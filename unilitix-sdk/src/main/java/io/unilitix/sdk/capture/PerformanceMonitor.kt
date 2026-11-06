package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Choreographer
import io.unilitix.sdk.util.Logger
import java.io.File
import kotlin.math.roundToInt

/**
 * Memory, CPU and frame drops attached to every event.
 *
 * From 1.10.2 the per-frame Choreographer callback runs only while at least
 * one activity is resumed (it used to re-post itself every frame for the life
 * of the process, keeping the app's main thread waking up in the background),
 * and the drop count is per session ([resetFrameDrops] on session start).
 * Registered with the SDK's other activity lifecycle callbacks.
 */
internal class PerformanceMonitor(
    /** Posts / removes a frame callback; Choreographer outside tests. Main thread. */
    private val postFrame: (Choreographer.FrameCallback) -> Unit = { Choreographer.getInstance().postFrameCallback(it) },
    private val removeFrame: (Choreographer.FrameCallback) -> Unit = { Choreographer.getInstance().removeFrameCallback(it) },
) : Application.ActivityLifecycleCallbacks {

    @Volatile var memoryUsageMb: Float = 0f
        private set

    @Volatile var cpuUsagePct: Float = 0f
        private set

    val frameDropCount: Int
        get() = frameDrops.count

    private val frameDrops = FrameDropCounter()
    private var prevCpuTotal: Long = 0L
    private var prevCpuWork: Long = 0L

    /** Activities resumed now (several in multi-window). Main thread writes. */
    @Volatile private var resumedCount = 0
    private var framesRunning = false

    /** True while frame callbacks are being posted. */
    internal val isTrackingFrames: Boolean
        get() = framesRunning

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!framesRunning) return
            try {
                if (frameDrops.onFrame(frameTimeNanos)) {
                    Logger.d("PerformanceMonitor: frame drop detected")
                }
                postFrame(this)
            } catch (t: Throwable) {
                // Never let SDK bookkeeping throw out of the host's frame.
                framesRunning = false
            }
        }
    }

    /** An activity resumed (main thread): frame tracking runs from now on. */
    internal fun onResumed() {
        resumedCount++
        try { updateMemory() } catch (_: Throwable) {}
        startFrames()
    }

    /** An activity paused (main thread): tracking stops when none is resumed. */
    internal fun onPaused() {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
        if (resumedCount == 0) stopFrames()
    }

    private fun startFrames() {
        if (framesRunning) return
        framesRunning = true
        // The gap while nothing was on screen is not a dropped frame.
        frameDrops.restart()
        try {
            postFrame(frameCallback)
        } catch (t: Throwable) {
            framesRunning = false
        }
    }

    private fun stopFrames() {
        if (!framesRunning) return
        framesRunning = false
        try { removeFrame(frameCallback) } catch (_: Throwable) {}
    }

    /** Stops frame tracking for good (SDK shutdown after a failed init). */
    fun stop() {
        resumedCount = 0
        stopFrames()
    }

    /** Every 5 s from the SDK scheduler thread; skipped while backgrounded. */
    fun sampleCpu() {
        if (resumedCount == 0) return
        updateMemory()
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

    /** New session: frame drops are counted afresh. */
    fun resetFrameDrops() {
        frameDrops.reset()
    }

    private fun updateMemory() {
        val rt = Runtime.getRuntime()
        val usedBytes = rt.totalMemory() - rt.freeMemory()
        memoryUsageMb = (usedBytes.toFloat() / (1024 * 1024) * 10).roundToInt() / 10f
    }

    override fun onActivityResumed(activity: Activity) = onResumed()
    override fun onActivityPaused(activity: Activity) = onPaused()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/**
 * Counts frames that took longer than [thresholdMs] (two 60 Hz frames). Pure
 * logic, so it is unit-testable; main thread only, [count] readable anywhere.
 */
internal class FrameDropCounter(private val thresholdMs: Long = 32L) {
    @Volatile var count: Int = 0
        private set

    private var lastFrameTimeNs = 0L

    /** Records a frame; true when it was a drop. */
    fun onFrame(frameTimeNanos: Long): Boolean {
        val last = lastFrameTimeNs
        lastFrameTimeNs = frameTimeNanos
        if (last <= 0L) return false
        val deltaMs = (frameTimeNanos - last) / 1_000_000L
        if (deltaMs > thresholdMs) {
            count++
            return true
        }
        return false
    }

    /** Tracking resumes after a pause: the next frame starts a new baseline. */
    fun restart() {
        lastFrameTimeNs = 0L
    }

    /** New session. */
    fun reset() {
        count = 0
        lastFrameTimeNs = 0L
    }
}
