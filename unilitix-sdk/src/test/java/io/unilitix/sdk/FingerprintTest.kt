package io.unilitix.sdk

import io.unilitix.sdk.core.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FingerprintTest {

    private val jvm = """
        java.lang.IllegalStateException: boom
            at com.shop.CheckoutActivity.pay(CheckoutActivity.kt:120)
            at android.view.View.performClick(View.java:7441)
            at android.view.View.performClickInternal(View.java:7418)
    """.trimIndent()

    private val hermes = """
        Error: boom
            at pay (address at index.android.bundle:1:234567)
            at onPress (address at index.android.bundle:1:123456)
            at _callTimer (address at index.android.bundle:1:98765)
    """.trimIndent()

    @Test
    fun `ignores line numbers`() {
        val moved = jvm.replace("CheckoutActivity.kt:120", "CheckoutActivity.kt:131")
        assertEquals(Fingerprint.of("IllegalStateException", jvm), Fingerprint.of("IllegalStateException", moved))
        assertEquals(16, Fingerprint.of("IllegalStateException", jvm).length)
    }

    @Test
    fun `hermes offsets are ignored, function names are not`() {
        val recompiled = hermes.replace("1:234567", "1:999999")
        assertEquals(Fingerprint.of("Error", hermes), Fingerprint.of("Error", recompiled))
        assertNotEquals(Fingerprint.of("Error", hermes), Fingerprint.of("Error", hermes.replace("at pay", "at refund")))
    }

    @Test
    fun `type matters`() {
        assertNotEquals(Fingerprint.of("Error", hermes), Fingerprint.of("TypeError", hermes))
    }

    @Test
    fun `is stable and 16 hex characters`() {
        assertEquals(Fingerprint.of("X", null), Fingerprint.of("X", ""))
        assertEquals(16, Fingerprint.of("X", null).length)
        assert(Fingerprint.of("X", null).matches(Regex("[0-9a-f]{16}")))
    }
}
