package io.unilitix.sdk.benchmark

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.unilitix.sdk.Unilitix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackBenchmark {

    /** A hang fails this test by name, with the main thread's stack, instead of stalling CI. */
    @get:Rule
    val hangGuard = HangGuard(120)

    private val props = mapOf<String, Any?>("sku" to "SKU-1042", "price" to 12500, "currency" to "NGN", "express" to true)

    @Test
    fun trackEvent_caller_cost_is_within_budget() {
        PerfBudget.ensureInit()
        repeat(500) { Unilitix.trackEvent("warmup", props) }
        val samples = ArrayList<Double>(5_000)
        repeat(5_000) { i ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            Unilitix.trackEvent("add_to_cart", props)
            samples += (SystemClock.elapsedRealtimeNanos() - t0) / 1e3
            if (i % 500 == 0) SystemClock.sleep(1)
        }
        // The caller only hands the call to the engine thread.
        PerfBudget.report("track_caller_p50_us", PerfBudget.percentile(samples, 0.50), "us", 30.0)
        PerfBudget.report("track_caller_p95_us", PerfBudget.percentile(samples, 0.95), "us", 250.0)
    }

    @Test
    fun engine_persists_a_thousand_events_quickly() {
        PerfBudget.ensureInit()
        val t0 = SystemClock.elapsedRealtimeNanos()
        repeat(1_000) { Unilitix.trackEvent("bulk", props) }
        // The engine is serial: once a task queued after the burst has run, the burst is on disk.
        Unilitix.core.engine!!.awaitIdle(30_000)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        PerfBudget.report("track_persist_1000_ms", ms, "ms", 3_000.0)
    }
}
