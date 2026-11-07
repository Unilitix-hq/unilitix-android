package io.unilitix.sdk.platform

import android.content.Context
import android.os.SystemClock
import io.unilitix.sdk.capture.PerformanceMonitor
import io.unilitix.sdk.context.BatteryInfo
import io.unilitix.sdk.context.DeviceInfo
import io.unilitix.sdk.context.carrierName
import io.unilitix.sdk.context.collectDeviceInfo
import io.unilitix.sdk.engine.Cancellable
import io.unilitix.sdk.engine.DeviceSource
import io.unilitix.sdk.engine.EngineClock
import io.unilitix.sdk.engine.EngineExecutor
import io.unilitix.sdk.engine.EngineScheduler
import io.unilitix.sdk.util.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Wall clock and `SystemClock.elapsedRealtime` (monotonic, counts deep sleep). */
internal object AndroidClock : EngineClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
}

/** The engine's own thread. Work items never throw out of it (the engine catches). */
internal class ThreadEngineExecutor : EngineExecutor {
    @Volatile private var thread: Thread? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unilitix-engine").also {
            it.isDaemon = true
            thread = it
        }
    }

    override fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                Logger.e("Unilitix: engine task failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun isEngineThread(): Boolean = Thread.currentThread() === thread

    override fun shutdown() {
        executor.shutdown()
    }
}

/** Timers on one daemon thread; cancelled tasks are removed at once. */
internal class ThreadEngineScheduler : EngineScheduler {
    private val pool = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "unilitix-timer").also { it.isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMs: Long, periodMs: Long, task: () -> Unit): Cancellable {
        val safe = Runnable {
            // An exception would cancel a repeating task silently.
            try { task() } catch (t: Throwable) { Logger.d("Unilitix: timer task failed: ${t.message}") }
        }
        val future = if (periodMs > 0) {
            pool.scheduleAtFixedRate(safe, delayMs.coerceAtLeast(0), periodMs, TimeUnit.MILLISECONDS)
        } else {
            pool.schedule(safe, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
        return Cancellable { future.cancel(false) }
    }

    override fun shutdown() {
        pool.shutdownNow()
    }
}

/**
 * Device and app fields of every session record, and the performance fields
 * of every event. The static part is read once, on the engine thread.
 */
internal class AndroidDeviceSource(
    private val context: Context,
    private val performance: PerformanceMonitor,
    private val battery: BatteryInfo,
) : DeviceSource {

    @Volatile private var info: DeviceInfo? = null

    private fun info(): DeviceInfo? = info ?: try {
        collectDeviceInfo(context).also { info = it }
    } catch (t: Throwable) {
        Logger.d("Unilitix: device info unavailable: ${t.message}")
        null
    }

    override fun sessionFields(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["deviceType"] = "phone"
        info()?.let { d ->
            m["manufacturer"] = d.manufacturer
            m["deviceModel"] = d.model
            m["os"] = d.os
            m["osVersion"] = d.osVersion
            m["screenWidth"] = d.screenWidth
            m["screenHeight"] = d.screenHeight
            m["screenDensity"] = d.screenDensity.toDouble()
            m["appVersion"] = d.appVersion
            m["buildNumber"] = d.buildNumber
            m["packageName"] = d.packageName
            m["sdkVersion"] = d.sdkVersion
            m["orientation"] = currentOrientation() ?: d.orientation
            m["locale"] = d.locale
            m["timezone"] = d.timezone
            m["totalStorageGb"] = d.totalStorageGb
        }
        m["carrierName"] = carrierName(context) ?: ""
        m["country"] = ""
        m["city"] = ""
        val level = battery.getBatteryLevel()
        m["batteryLevel"] = if (level >= 0) level.toDouble() else null
        return m
    }

    private fun currentOrientation(): String? = try {
        if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"
    } catch (_: Throwable) {
        null
    }

    override fun eventFields(): Map<String, Any?> = linkedMapOf(
        "memoryUsageMb" to performance.memoryUsageMb.toDouble(),
        "cpuUsagePct" to performance.cpuUsagePct.toDouble(),
        "frameDrops" to performance.frameDropCount,
    )
}
