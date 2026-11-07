package io.unilitix.sdk.benchmark

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.capture.ReplayState
import io.unilitix.sdk.capture.ScreenshotCapture
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenshotBenchmark {

    /** A hang fails this test by name instead of stalling the whole CI step. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Test
    fun screenshot_capture_cost_is_within_budget() {
        val config = UnilitixConfig.Builder().build()
        val replayState = ReplayState(10_000, 10_000, 10_000)
        var session = 0
        var latch = CountDownLatch(1)
        var bytes = 0
        val capture = ScreenshotCapture(
            config,
            isEnabled = { true },
            // A new session each time: the scheduler always wants its first frame.
            sessionId = { "bench-$session" },
            replayState = replayState,
        ) { _, shot -> bytes = shot.imageBytes.size; latch.countDown() }

        val mainSamples = ArrayList<Double>()
        val totalSamples = ArrayList<Double>()
        ActivityScenario.launch(BenchmarkActivity::class.java).use { scenario ->
            // Not waitForIdleSync(): with the SDK running, the main thread is rarely
            // fully idle (watchdog pings, frame callbacks), so it may never return.
            var focused = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!focused && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (!focused) SystemClock.sleep(100)
            }
            assertTrue("benchmark activity never got window focus (screen locked?)", focused)
            repeat(25) { i ->
                session = i
                latch = CountDownLatch(1)
                val t0 = SystemClock.elapsedRealtimeNanos()
                scenario.onActivity { activity ->
                    val m0 = SystemClock.elapsedRealtimeNanos()
                    capture.captureForTest(activity)
                    mainSamples += (SystemClock.elapsedRealtimeNanos() - m0) / 1e6
                }
                assertTrue("frame $i captured", latch.await(5, TimeUnit.SECONDS))
                totalSamples += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }
        }
        val warm = mainSamples.drop(5)
        PerfBudget.report("screenshot_main_thread_p50_ms", PerfBudget.percentile(warm, 0.5), "ms", 8.0)
        PerfBudget.report("screenshot_main_thread_p95_ms", PerfBudget.percentile(warm, 0.95), "ms", 16.0)
        PerfBudget.report("screenshot_total_p50_ms", PerfBudget.percentile(totalSamples.drop(5), 0.5), "ms", 120.0)
        PerfBudget.report("screenshot_bytes", bytes.toDouble(), "bytes", 150_000.0)
    }
}
