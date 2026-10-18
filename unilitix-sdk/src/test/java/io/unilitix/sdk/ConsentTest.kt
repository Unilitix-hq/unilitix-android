package io.unilitix.sdk

import io.unilitix.sdk.contract.SdkContract
import io.unilitix.sdk.core.ConsentController
import io.unilitix.sdk.core.ConsentEffects
import io.unilitix.sdk.core.ConsentStore
import io.unilitix.sdk.core.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTest {

    /** SharedPreferences stand-in; [backing] survives "relaunches". */
    private class MemoryStore(val backing: MutableMap<String, Any> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = backing[key] as? String
        override fun getBoolean(key: String, default: Boolean): Boolean = backing[key] as? Boolean ?: default
        override fun putString(key: String, value: String) { backing[key] = value }
        override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    }

    /**
     * A device with an on-device queue and a server. Mirrors what
     * UnilitixInternal / FlushWorker do with the controller's gates.
     */
    private class Device(kv: KeyValueStore, initial: UnilitixConsent) : ConsentEffects {
        val queue = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        var capturing = true
        var purges = 0
        val consent = ConsentController(ConsentStore(kv), initial, this)

        fun capture(item: String) {
            if (consent.canCapture) queue.add(item)
        }

        /** FlushWorker: upload and dequeue only when consent allows it. */
        fun flush() {
            if (!consent.canUpload) return
            uploaded.addAll(queue)
            queue.clear()
        }

        override fun startCapture() { capturing = true }
        override fun stopCapture() { capturing = false }
        override fun purgeLocalData() { purges++; queue.clear() }
        override fun releaseHeld() { flush() }
    }

    @Test
    fun `wire values match the contract`() {
        assertEquals(SdkContract.consentStates.toSet(), UnilitixConsent.values().map { it.value }.toSet())
        assertEquals(SdkContract.DEFAULT_CONSENT, UnilitixConfig.Builder().consent.value)
        assertEquals(SdkContract.DEFAULT_CONSENT, UnilitixConsent.GRANTED.value)
        assertEquals(UnilitixConsent.PENDING, UnilitixConsent.fromValue("pending"))
        assertEquals(UnilitixConsent.DENIED, UnilitixConsent.fromValue(" Denied "))
        assertNull(UnilitixConsent.fromValue("maybe"))
        assertNull(UnilitixConsent.fromValue(null))
    }

    @Test
    fun `default is granted on a fresh install`() {
        val c = Device(MemoryStore(), UnilitixConsent.GRANTED).consent
        assertEquals(UnilitixConsent.GRANTED, c.state)
        assertTrue(c.canCapture)
        assertTrue(c.canUpload)
    }

    @Test
    fun `init value applies until a decision is persisted, then the decision wins`() {
        val prefs = mutableMapOf<String, Any>()
        assertEquals(UnilitixConsent.PENDING, Device(MemoryStore(prefs), UnilitixConsent.PENDING).consent.state)
        // Not persisted: the next launch's init value still applies.
        assertEquals(UnilitixConsent.GRANTED, Device(MemoryStore(prefs), UnilitixConsent.GRANTED).consent.state)

        Device(MemoryStore(prefs), UnilitixConsent.PENDING).consent.set(UnilitixConsent.DENIED)
        assertEquals("denied", prefs[ConsentStore.KEY_CONSENT])
        assertEquals(true, prefs[ConsentStore.KEY_LEGACY_OPT_OUT])

        // Later launch asks for granted: the persisted denial wins.
        assertEquals(UnilitixConsent.DENIED, Device(MemoryStore(prefs), UnilitixConsent.GRANTED).consent.state)

        Device(MemoryStore(prefs), UnilitixConsent.GRANTED).consent.set(UnilitixConsent.GRANTED)
        assertEquals(false, prefs[ConsentStore.KEY_LEGACY_OPT_OUT])
        assertEquals(UnilitixConsent.GRANTED, Device(MemoryStore(prefs), UnilitixConsent.PENDING).consent.state)
    }

    @Test
    fun `legacy opt-out flag true migrates to denied`() {
        val prefs = mutableMapOf<String, Any>(ConsentStore.KEY_LEGACY_OPT_OUT to true)
        val device = Device(MemoryStore(prefs), UnilitixConsent.GRANTED)
        assertEquals(UnilitixConsent.DENIED, device.consent.state)
        assertFalse(device.consent.canCapture)
        assertEquals("denied", prefs[ConsentStore.KEY_CONSENT])
    }

    @Test
    fun `legacy opt-out flag false is not a decision`() {
        val prefs = mutableMapOf<String, Any>(ConsentStore.KEY_LEGACY_OPT_OUT to false)
        assertEquals(UnilitixConsent.PENDING, Device(MemoryStore(prefs), UnilitixConsent.PENDING).consent.state)
        assertEquals(UnilitixConsent.GRANTED, Device(MemoryStore(prefs), UnilitixConsent.GRANTED).consent.state)
    }

    @Test
    fun `unknown stored value falls back to the init value`() {
        val prefs = mutableMapOf<String, Any>(ConsentStore.KEY_CONSENT to "bogus")
        assertEquals(UnilitixConsent.PENDING, Device(MemoryStore(prefs), UnilitixConsent.PENDING).consent.state)
    }

    @Test
    fun `pending captures but uploads nothing`() {
        val d = Device(MemoryStore(), UnilitixConsent.PENDING)
        d.capture("tap")
        d.capture("snapshot")
        d.capture("screenshot")
        d.flush()
        assertEquals(listOf("tap", "snapshot", "screenshot"), d.queue)
        assertTrue(d.uploaded.isEmpty())
        assertTrue(d.consent.canCapture)
        assertFalse(d.consent.canUpload)
    }

    @Test
    fun `granted after pending releases everything held`() {
        val d = Device(MemoryStore(), UnilitixConsent.PENDING)
        d.capture("tap")
        d.capture("screenshot")
        assertEquals(UnilitixConsent.PENDING, d.consent.set(UnilitixConsent.GRANTED))
        assertEquals(listOf("tap", "screenshot"), d.uploaded)
        assertTrue(d.queue.isEmpty())
        d.capture("after")
        d.flush()
        assertEquals(listOf("tap", "screenshot", "after"), d.uploaded)
    }

    @Test
    fun `denied purges the queue and stops capture`() {
        val d = Device(MemoryStore(), UnilitixConsent.PENDING)
        d.capture("tap")
        d.capture("screenshot")
        d.consent.set(UnilitixConsent.DENIED)
        assertTrue(d.queue.isEmpty())
        assertEquals(1, d.purges)
        assertFalse(d.capturing)
        d.capture("ignored")
        d.flush()
        assertTrue(d.queue.isEmpty())
        assertTrue(d.uploaded.isEmpty())
    }

    @Test
    fun `granted after denied resumes capture without resurrecting purged data`() {
        val d = Device(MemoryStore(), UnilitixConsent.GRANTED)
        d.consent.set(UnilitixConsent.DENIED)
        d.consent.set(UnilitixConsent.GRANTED)
        assertTrue(d.capturing)
        d.capture("new")
        d.flush()
        assertEquals(listOf("new"), d.uploaded)
    }

    @Test
    fun `pending after granted holds new data without deleting it`() {
        val d = Device(MemoryStore(), UnilitixConsent.GRANTED)
        d.consent.set(UnilitixConsent.PENDING)
        d.capture("held")
        d.flush()
        assertEquals(listOf("held"), d.queue)
        assertEquals(0, d.purges)
    }
}
