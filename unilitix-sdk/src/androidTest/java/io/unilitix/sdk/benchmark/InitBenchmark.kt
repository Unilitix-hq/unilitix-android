package io.unilitix.sdk.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.unilitix.sdk.Unilitix
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitBenchmark {

    @Test
    fun init_main_thread_cost_is_within_budget() {
        PerfBudget.ensureInit()
        // Only the crash handler and lifecycle registrations run on the main thread.
        PerfBudget.report("init_main_thread_ms", PerfBudget.initMainThreadMs!!, "ms", 30.0)
    }

    @Test
    fun sdk_is_ready_soon_after_init() {
        PerfBudget.ensureInit()
        assertNotNull("first session started", Unilitix.currentSessionId())
        PerfBudget.report("init_ready_ms", PerfBudget.initReadyMs!!, "ms", 1_500.0)
    }
}
