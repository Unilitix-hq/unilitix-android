package io.unilitix.sdk.benchmark

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.unilitix.sdk.Unilitix
import org.junit.Assert.assertTrue

/**
 * Budgets from docs/performance.md, measured on the CI emulators (API 24 and
 * 34, x86_64, no hardware acceleration guarantees). A slower runner can scale
 * every budget with the instrumentation argument `unilitixBudgetScale`
 * (e.g. `-Pandroid.testInstrumentationRunnerArguments.unilitixBudgetScale=2`).
 * Results are printed as `UNILITIX_BENCH name=... value=...` lines for CI to collect.
 */
object PerfBudget {

    val scale: Double by lazy {
        InstrumentationRegistry.getArguments().getString("unilitixBudgetScale")?.toDoubleOrNull() ?: 1.0
    }

    fun report(name: String, value: Double, unit: String, budget: Double) {
        val limit = budget * scale
        println("UNILITIX_BENCH name=$name value=${"%.3f".format(value)} unit=$unit budget=${"%.3f".format(limit)}")
        assertTrue("$name = ${"%.3f".format(value)} $unit, budget ${"%.3f".format(limit)} $unit", value <= limit)
    }

    fun percentile(samples: List<Double>, p: Double): Double {
        val sorted = samples.sorted()
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** Main-thread time of the first Unilitix.init in this process, in ms (null until measured). */
    @Volatile var initMainThreadMs: Double? = null
        private set
    @Volatile var initReadyMs: Double? = null
        private set

    /** Initialises the SDK once for every benchmark in the process, measuring it. */
    @Synchronized
    fun ensureInit() {
        if (initMainThreadMs != null) return
        val app = ApplicationProvider.getApplicationContext<Application>()
        var mainMs = 0.0
        val start = SystemClock.elapsedRealtimeNanos()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t0 = SystemClock.elapsedRealtimeNanos()
            Unilitix.init(app, "ul_test_benchmark") {
                // Unreachable on purpose: benchmarks measure the device, not the network.
                apiUrl = "http://127.0.0.1:9"
                flushIntervalSeconds = 3600
            }
            mainMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }
        initMainThreadMs = mainMs
        // Engine ready: storage opened, earlier crashes read, first session started.
        while (Unilitix.currentSessionId() == null && SystemClock.elapsedRealtimeNanos() - start < 10_000_000_000L) {
            SystemClock.sleep(2)
        }
        initReadyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }
}
