package io.unilitix.sdk

import io.unilitix.sdk.capture.ReplayScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySchedulerTest {

    private var now = 1_000_000L

    private fun scheduler(intervalMs: Long = 1_000, heartbeatMs: Long = 10_000, maxPerMinute: Int = 20) =
        ReplayScheduler(intervalMs, heartbeatMs, maxPerMinute) { now }

    @Test
    fun `first frame of a session is taken immediately`() {
        val s = scheduler()
        assertTrue(s.shouldCapture("a"))
        assertFalse("static screen, inside the interval", s.shouldCapture("a"))
    }

    @Test
    fun `a new session gets a first frame immediately`() {
        val s = scheduler()
        assertTrue(s.shouldCapture("a"))
        now += 10
        assertTrue(s.shouldCapture("b"))
    }

    @Test
    fun `a change is captured only once the interval has passed`() {
        val s = scheduler()
        assertTrue(s.shouldCapture("a"))
        s.markDirty()
        now += 999
        assertFalse(s.shouldCapture("a"))
        now += 1
        assertTrue(s.shouldCapture("a"))
        now += 5_000
        assertFalse("no change since the last frame", s.shouldCapture("a"))
    }

    @Test
    fun `per-minute budget caps a continuously changing screen`() {
        val s = scheduler(intervalMs = 1_000, maxPerMinute = 20)
        var taken = 0
        repeat(60) {
            s.markDirty()
            if (s.shouldCapture("a")) taken++
            now += 1_000
        }
        assertEquals(20, taken)
        // The window rolls: the first frames leave it and the budget comes back.
        s.markDirty()
        assertTrue(s.shouldCapture("a"))
    }

    @Test
    fun `budget is rolling, not per calendar minute`() {
        val s = scheduler(intervalMs = 0, maxPerMinute = 3)
        repeat(3) { s.markDirty(); assertTrue(s.shouldCapture("a")); now += 100 }
        s.markDirty()
        assertFalse(s.shouldCapture("a"))
        now = 1_000_000L + 60_000L // first frame is now 60 s old
        assertTrue("dirty flag survives a refusal", s.shouldCapture("a"))
        assertFalse(s.let { it.markDirty(); it.shouldCapture("a") })
    }

    @Test
    fun `heartbeat takes a frame on a static screen`() {
        val s = scheduler(heartbeatMs = 10_000)
        assertTrue(s.shouldCapture("a"))
        now += 9_999
        assertFalse(s.shouldCapture("a"))
        now += 1
        assertTrue(s.shouldCapture("a"))
        now += 10_000
        assertTrue(s.shouldCapture("a"))
    }

    @Test
    fun `heartbeat 0 disables it`() {
        val s = scheduler(heartbeatMs = 0)
        assertTrue(s.shouldCapture("a"))
        now += 3_600_000
        assertFalse(s.shouldCapture("a"))
        s.markDirty()
        assertTrue(s.shouldCapture("a"))
    }

    @Test
    fun `heartbeat is never shorter than the interval`() {
        val s = scheduler(intervalMs = 5_000, heartbeatMs = 1_000)
        assertEquals(5_000L, s.heartbeatMs)
        assertTrue(s.shouldCapture("a"))
        now += 4_999
        assertFalse(s.shouldCapture("a"))
        now += 1
        assertTrue(s.shouldCapture("a"))
    }

    @Test
    fun `heartbeat frames count against the budget`() {
        val s = scheduler(intervalMs = 1_000, heartbeatMs = 1_000, maxPerMinute = 2)
        assertTrue(s.shouldCapture("a"))
        now += 1_000
        assertTrue(s.shouldCapture("a"))
        now += 1_000
        assertFalse(s.shouldCapture("a"))
    }

    @Test
    fun `contract defaults`() {
        val c = UnilitixConfig.Builder().build()
        val s = ReplayScheduler(c.screenshotIntervalMs, c.screenshotHeartbeatMs, c.maxScreenshotsPerMinute) { now }
        assertEquals(1_000L, s.intervalMs)
        assertEquals(10_000L, s.heartbeatMs)
        assertEquals(20, s.maxPerMinute)
    }
}
