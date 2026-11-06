package io.unilitix.sdk

import android.view.Choreographer
import io.unilitix.sdk.capture.FrameDropCounter
import io.unilitix.sdk.capture.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.10.2: frame callbacks only while an activity is resumed; drops per session. */
class PerformanceMonitorTest {

    private val ms = 1_000_000L

    @Test
    fun `counts frames slower than 32 ms`() {
        val c = FrameDropCounter()
        assertFalse("first frame is the baseline", c.onFrame(1_000 * ms))
        assertFalse(c.onFrame(1_016 * ms))
        assertTrue(c.onFrame(1_100 * ms))
        assertEquals(1, c.count)
    }

    @Test
    fun `the gap after a pause is not a drop and reset clears the count`() {
        val c = FrameDropCounter()
        c.onFrame(1_000 * ms)
        c.onFrame(1_100 * ms)
        assertEquals(1, c.count)
        c.restart()
        assertFalse("first frame after resuming", c.onFrame(60_000 * ms))
        assertEquals(1, c.count)
        c.reset()
        assertEquals(0, c.count)
    }

    private class FakeChoreographer {
        val posted = mutableListOf<Choreographer.FrameCallback>()
        var removed = 0
        fun frame(timeNs: Long) {
            val pending = posted.toList()
            posted.clear()
            pending.forEach { it.doFrame(timeNs) }
        }
    }

    private fun monitor(fake: FakeChoreographer) =
        PerformanceMonitor(postFrame = { fake.posted.add(it) }, removeFrame = { fake.removed++; fake.posted.remove(it) })

    @Test
    fun `no frame callback until an activity resumes`() {
        val fake = FakeChoreographer()
        val m = monitor(fake)
        assertFalse(m.isTrackingFrames)
        assertTrue(fake.posted.isEmpty())
        m.onResumed()
        assertTrue(m.isTrackingFrames)
        assertEquals(1, fake.posted.size)
        fake.frame(1_000 * ms)
        assertEquals("re-posts itself while resumed", 1, fake.posted.size)
    }

    @Test
    fun `frame callbacks stop when the last activity pauses`() {
        val fake = FakeChoreographer()
        val m = monitor(fake)
        m.onResumed()
        m.onResumed() // multi-window: two resumed
        m.onPaused()
        assertTrue("one activity still resumed", m.isTrackingFrames)
        m.onPaused()
        assertFalse(m.isTrackingFrames)
        assertTrue(fake.posted.isEmpty())
        fake.frame(2_000 * ms) // nothing left to run
        assertTrue(fake.posted.isEmpty())
        m.onPaused() // extra pause never goes negative
        m.onResumed()
        assertTrue(m.isTrackingFrames)
    }

    @Test
    fun `background time is not counted as a drop and drops reset per session`() {
        val fake = FakeChoreographer()
        val m = monitor(fake)
        m.onResumed()
        fake.frame(1_000 * ms)
        fake.frame(1_100 * ms)
        assertEquals(1, m.frameDropCount)
        m.onPaused()
        m.onResumed()
        fake.frame(90_000 * ms)
        fake.frame(90_016 * ms)
        assertEquals(1, m.frameDropCount)
        m.resetFrameDrops()
        assertEquals(0, m.frameDropCount)
    }
}
