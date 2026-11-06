package io.unilitix.sdk

import io.unilitix.sdk.capture.CaptureFailureLatch
import io.unilitix.sdk.capture.ScreenshotSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.10.2: screenshots are allocated at the upload size; OOM turns capture off. */
class ScreenshotSafetyTest {

    @Test
    fun `a large window is drawn at maxWidth keeping the aspect ratio`() {
        val t = ScreenshotSize.target(1440, 3200, 480)!!
        assertEquals(480, t.width)
        assertEquals(1066, t.height) // same rounding as the old createScaledBitmap path
        assertEquals(1f / 3f, t.scale, 1e-6f)
        // About 2 MB at ARGB_8888 instead of ~18 MB for the full frame (and its copies).
        assertTrue(t.width.toLong() * t.height * 4 < 2_100_000L)
    }

    @Test
    fun `a window narrower than maxWidth is not scaled up`() {
        val t = ScreenshotSize.target(320, 640, 480)!!
        assertEquals(320, t.width)
        assertEquals(640, t.height)
        assertEquals(1f, t.scale, 0f)
    }

    @Test
    fun `no bitmap for an empty window, and never a zero-sized one`() {
        assertNull(ScreenshotSize.target(0, 100, 480))
        assertNull(ScreenshotSize.target(100, 0, 480))
        val thin = ScreenshotSize.target(10_000, 1, 480)!!
        assertEquals(1, thin.height)
        val t = ScreenshotSize.target(1080, 1920, 0)!!
        assertEquals(1, t.width)
        assertTrue(t.height >= 1)
    }

    @Test
    fun `an out-of-memory error disables capture, other failures do not`() {
        val latch = CaptureFailureLatch()
        assertFalse(latch.record(IllegalArgumentException("hardware bitmap")))
        assertFalse(latch.disabled)
        assertTrue(latch.record(OutOfMemoryError("bitmap")))
        assertTrue(latch.disabled)
        assertTrue("stays off", latch.record(RuntimeException()))
    }
}
