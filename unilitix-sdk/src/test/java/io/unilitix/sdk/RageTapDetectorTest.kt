package io.unilitix.sdk

import io.unilitix.sdk.capture.RageTapDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RageTapDetectorTest {

    @Test
    fun `three taps in radius triggers rage tap`() {
        var rageCount = 0
        val detector = RageTapDetector { _, _, _ -> rageCount++ }

        assertFalse(detector.recordTap(100f, 100f, "Home"))
        assertFalse(detector.recordTap(105f, 105f, "Home"))
        assertTrue(detector.recordTap(110f, 110f, "Home"))
        assertEquals(1, rageCount)
    }

    @Test
    fun `taps outside radius do not trigger rage tap`() {
        var rageCount = 0
        val detector = RageTapDetector { _, _, _ -> rageCount++ }

        assertFalse(detector.recordTap(100f, 100f, "Home"))
        assertFalse(detector.recordTap(300f, 300f, "Home"))
        assertFalse(detector.recordTap(500f, 500f, "Home"))
        assertEquals(0, rageCount)
    }

    @Test
    fun `taps on different screens do not cluster`() {
        var rageCount = 0
        val detector = RageTapDetector { _, _, _ -> rageCount++ }

        assertFalse(detector.recordTap(100f, 100f, "Home"))
        assertFalse(detector.recordTap(100f, 100f, "Product"))
        assertFalse(detector.recordTap(100f, 100f, "Checkout"))
        assertEquals(0, rageCount)
    }
}
