package io.unilitix.sdk.benchmark

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.capture.ReplayState
import io.unilitix.sdk.capture.ScreenshotCapture
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenshotBenchmark {

    /** A hang fails this test by name, with the main thread's stack, instead of stalling CI. */
    @get:Rule
    val hangGuard = HangGuard(120)

    @Test
    fun screenshot_capture_cost_is_within_budget() {
        // The default 20-a-minute budget would refuse frames part-way through
        // the 25 below (that cap is tested elsewhere); measure capture cost only.
        val config = UnilitixConfig.Builder()
            .maxScreenshotsPerMinute(10_000)
            .screenshotIntervalMs(0)
            .build()
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
        // Not ActivityScenario: since Android 13 it waits (startActivitySync) for
        // the enter animation to finish, which can never be reported on a
        // headless emulator with animations off, and the test hangs there.
        val activity = launchBenchmarkActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            // Not waitForIdleSync(): with the SDK running, the main thread is rarely
            // fully idle (watchdog pings, frame callbacks), so it may never return.
            var focused = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!focused && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                if (!focused) SystemClock.sleep(100)
            }
            assertTrue("benchmark activity never got window focus (screen locked?)", focused)
            repeat(25) { i ->
                session = i
                latch = CountDownLatch(1)
                val t0 = SystemClock.elapsedRealtimeNanos()
                instrumentation.runOnMainSync {
                    val m0 = SystemClock.elapsedRealtimeNanos()
                    capture.captureForTest(activity)
                    mainSamples += (SystemClock.elapsedRealtimeNanos() - m0) / 1e6
                }
                assertTrue("frame $i captured", latch.await(5, TimeUnit.SECONDS))
                totalSamples += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        val warm = mainSamples.drop(5)
        PerfBudget.report("screenshot_main_thread_p50_ms", PerfBudget.percentile(warm, 0.5), "ms", 8.0)
        PerfBudget.report("screenshot_main_thread_p95_ms", PerfBudget.percentile(warm, 0.95), "ms", 16.0)
        PerfBudget.report("screenshot_total_p50_ms", PerfBudget.percentile(totalSamples.drop(5), 0.5), "ms", 120.0)
        PerfBudget.report("screenshot_bytes", bytes.toDouble(), "bytes", 150_000.0)
    }

    /** Starts [BenchmarkActivity] and returns it once resumed (at most 30 s). */
    private fun launchBenchmarkActivity(): Activity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val resumed = CountDownLatch(1)
        var launched: Activity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is BenchmarkActivity) {
                    launched = activity
                    resumed.countDown()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            app.startActivity(
                Intent(app, BenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue("benchmark activity was not resumed within 30 s", resumed.await(30, TimeUnit.SECONDS))
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
        return launched!!
    }
}
