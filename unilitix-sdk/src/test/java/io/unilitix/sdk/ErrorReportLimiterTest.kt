package io.unilitix.sdk

import io.unilitix.sdk.contract.SdkContract
import io.unilitix.sdk.core.ErrorReportLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorReportLimiterTest {

    @Test
    fun `defaults match the contract`() {
        val l = ErrorReportLimiter()
        assertEquals(SdkContract.MAX_REPORTS_PER_FINGERPRINT, l.maxPerFingerprint.toLong())
        assertEquals(SdkContract.MAX_ERROR_REPORTS_PER_SESSION, l.maxPerSession.toLong())
    }

    @Test
    fun `same fingerprint is reported 3 times per session`() {
        val l = ErrorReportLimiter()
        assertEquals(1, l.admit("fp"))
        assertEquals(2, l.admit("fp"))
        assertEquals(3, l.admit("fp"))
        assertNull(l.admit("fp"))
        assertNull(l.admit("fp"))
        assertEquals("suppressed ones are still counted", 5, l.occurrences("fp"))
        assertEquals("other fingerprints are independent", 1, l.admit("other"))
    }

    @Test
    fun `a new session resets the counts`() {
        val l = ErrorReportLimiter()
        repeat(4) { l.admit("fp") }
        l.resetSession()
        assertEquals(1, l.admit("fp"))
        assertEquals(1, l.occurrences("fp"))
    }

    @Test
    fun `total reports per session are capped`() {
        val l = ErrorReportLimiter()
        var sent = 0
        for (i in 0 until 80) if (l.admit("fp$i") != null) sent++
        assertEquals(50, sent)
        l.resetSession()
        assertEquals(1, l.admit("fp79"))
    }
}
