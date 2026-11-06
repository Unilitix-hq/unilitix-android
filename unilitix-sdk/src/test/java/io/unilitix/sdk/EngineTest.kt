package io.unilitix.sdk

import io.unilitix.sdk.capture.Anr
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.contract.BehaviourRunner.FakeClock
import io.unilitix.sdk.contract.BehaviourRunner.FakeDevice
import io.unilitix.sdk.contract.BehaviourRunner.FakeNetwork
import io.unilitix.sdk.contract.BehaviourRunner.FakeRandom
import io.unilitix.sdk.contract.BehaviourRunner.FakeScheduler
import io.unilitix.sdk.contract.BehaviourRunner.FakeTransport
import io.unilitix.sdk.contract.BehaviourRunner.InlineExecutor
import io.unilitix.sdk.engine.Engine
import io.unilitix.sdk.engine.EngineConfig
import io.unilitix.sdk.engine.EngineDeps
import io.unilitix.sdk.engine.EngineHooks
import io.unilitix.sdk.engine.ErrorReport
import io.unilitix.sdk.engine.MemoryEngineStore
import io.unilitix.sdk.engine.PriorBatch
import io.unilitix.sdk.engine.StartInputs
import io.unilitix.sdk.engine.UnilitixCore
import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Engine behaviour beyond the shared fixtures: crash and ANR attribution,
 * parking, request size, wrapper fields, install id, upgrades from 1.10.
 */
class EngineTest {

    private lateinit var previousSink: LogSink

    @Before
    fun quiet() {
        previousSink = Logger.sink
        Logger.sink = object : LogSink {
            override fun debug(tag: String, message: String) {}
            override fun info(tag: String, message: String) {}
            override fun warn(tag: String, message: String) {}
            override fun error(tag: String, message: String, throwable: Throwable?) {}
        }
    }

    @After
    fun restore() {
        Logger.sink = previousSink
    }

    private val t0 = 1_767_603_600_000L // 2026-01-05T09:00:00Z

    private inner class Device(val config: EngineConfig = EngineConfig(flushIntervalMs = 3_600_000, replayCapture = false)) {
        val clock = FakeClock(t0)
        val network = FakeNetwork("wifi", null, true)
        val transport = FakeTransport(network, 202)
        val store = MemoryEngineStore()
        val purges = intArrayOf(0)
        val hooks = object : EngineHooks {
            override fun onPurge() { purges[0]++ }
        }

        fun process(inputs: StartInputs = StartInputs()): Pair<UnilitixCore, Engine> {
            val deps = EngineDeps(clock, FakeScheduler(clock), InlineExecutor(), transport, network, FakeDevice, FakeRandom(emptyList()), store, hooks)
            val e = Engine(config, deps)
            val core = UnilitixCore()
            core.start(e, inputs)
            return core to e
        }

        @Suppress("UNCHECKED_CAST")
        fun sessionBodies() = transport.recorded.filter { it.path == Engine.PATH_SESSION }.map { it.body }

        @Suppress("UNCHECKED_CAST")
        fun events(): List<Map<String, Any?>> = transport.recorded
            .filter { it.path == Engine.PATH_SESSION && it.status in 200..299 }
            .flatMap { it.body["events"] as List<Map<String, Any?>> }
    }

    @Test
    fun `a native crash with no stored session goes into the new session after SESSION_START`() {
        val d = Device()
        val crash = NativeCrashRecord.parse("""{"platform":"android","exceptionType":"SIGNAL","signal":11,"timestamp":${t0 - 86_400_000}}""")!!
        val (core, e) = d.process(StartInputs(nativeCrashes = listOf(crash)))
        core.flush()
        val evs = d.events()
        assertEquals(listOf("SESSION_START", "CRASH"), evs.map { it["type"] })
        assertEquals(e.currentSessionId(), d.sessionBodies().last()["sessionId"])
        assertEquals(true, d.sessionBodies().last()["crashed"])
    }

    @Test
    fun `an ANR goes to the session that was running and does not mark it crashed`() {
        val d = Device()
        val (core1, e1) = d.process()
        val first = e1.currentSessionId()
        core1.flush()
        d.clock.advance(60_000)
        val anr = Anr("Input dispatching timed out", "\"main\" prio=5 tid=1 Native\n  at a.B.c(B.kt:1)", d.clock.wall, 100, "0123456789abcdef")
        val (core2, e2) = d.process(StartInputs(anrs = listOf(anr)))
        core2.flush()
        val anrBody = d.sessionBodies().first { b -> (b["events"] as List<*>).any { (it as Map<*, *>)["type"] == "ANR" } }
        assertEquals(first, anrBody["sessionId"])
        assertEquals(false, anrBody["crashed"])
        assertNotEquals(first, e2.currentSessionId())
    }

    @Test
    fun `a batch that keeps failing is parked so newer data goes, and retried on the next launch`() {
        val d = Device(EngineConfig(flushIntervalMs = 3_600_000, flushBatchSize = 2, replayCapture = false))
        val (core, _) = d.process()
        d.transport.respond(503, emptyMap(), Engine.MAX_BATCH_ATTEMPTS)
        core.track("a", null) // [SESSION_START, a]: a full batch, sent at once (attempt 1)
        repeat(Engine.MAX_BATCH_ATTEMPTS) {
            core.flush()
            d.clock.advance(400_000) // past any backoff window
        }
        assertEquals(2, d.store.eventCount())
        assertTrue("head batch parked: nothing left to send", d.store.oldestEvents(10).isEmpty())
        core.track("b", null)
        core.flush()
        assertEquals(listOf("CUSTOM"), d.events().map { it["type"] })
        // Next launch: the parked batch is queued again and goes first.
        val (core2, _) = d.process()
        core2.flush()
        assertTrue(d.events().map { it["type"] }.containsAll(listOf("SESSION_START", "CUSTOM")))
        assertEquals(0, d.store.eventCount())
    }

    @Test
    fun `a batch too large for one request is split, never refused with 413`() {
        val d = Device()
        val (core, _) = d.process()
        val big = (0 until 100).associate { "k$it" to "x".repeat(1024) }
        repeat(45) { core.track("big$it", big) } // ~4.6 MB of properties
        core.flush()
        val bodies = d.sessionBodies()
        assertTrue("split into several requests", bodies.size >= 2)
        assertEquals(46, d.events().size)
        assertEquals(0, d.store.eventCount())
    }

    @Test
    fun `wrapper name and version travel with the session`() {
        val d = Device()
        val (core, _) = d.process()
        core.setWrapper("flutter", "3.0.0")
        core.flush()
        val body = d.sessionBodies().last()
        assertEquals("flutter", body["sdkWrapper"])
        assertEquals("3.0.0", body["sdkWrapperVersion"])
        assertEquals(mapOf("sdkWrapper" to "flutter", "sdkWrapperVersion" to "3.0.0"), body["sessionData"])
    }

    @Test
    fun `external errors keep the wrapper's fingerprint and metadata`() {
        val d = Device()
        val (core, _) = d.process()
        core.recordError(ErrorReport("TypeError", "x", "at f (index.bundle:1:2)", fatal = false, platform = "react-native",
            fingerprint = "00112233aabbccdd", metadata = mapOf("jsBundleId" to "index.android.bundle", "fingerprint" to "ignored")))
        core.flush()
        @Suppress("UNCHECKED_CAST")
        val meta = d.events().last()["metadata"] as Map<String, Any?>
        assertEquals("00112233aabbccdd", meta["fingerprint"])
        assertEquals("react-native", meta["platform"])
        assertEquals("index.android.bundle", meta["jsBundleId"])
    }

    @Test
    fun `a fatal error is persisted and uploaded before recordFatal returns`() {
        val d = Device()
        val (core, e) = d.process()
        assertTrue(core.recordFatal(ErrorReport("java.lang.IllegalStateException", "boom", "at a.B.c(B.kt:1)", fatal = true), 2_000))
        assertEquals("CRASH", d.events().last()["type"])
        assertEquals(true, d.sessionBodies().last()["crashed"])
        assertEquals(0, e.queueLength())
    }

    @Test
    fun `reset rotates the install id and opt-out deletes it`() {
        val d = Device()
        val (core, _) = d.process()
        core.flush()
        val install1 = d.sessionBodies().last()["installId"] as String
        core.reset()
        core.flush()
        val install2 = d.sessionBodies().last()["installId"] as String
        assertNotEquals(install1, install2)
        core.setConsent(UnilitixConsent.DENIED)
        assertNull(d.store.device.getString(Engine.KEY_INSTALL_ID))
        assertEquals(1, d.purges[0])
    }

    @Test
    fun `identify is dropped while consent is denied`() {
        val d = Device(EngineConfig(flushIntervalMs = 3_600_000, replayCapture = false, initialConsent = UnilitixConsent.DENIED))
        val (core, e) = d.process()
        core.identify("u1", null)
        assertNull(e.currentUserId())
        assertNull(d.store.identity.getString(Engine.KEY_PENDING_IDENTIFIES))
    }

    @Test
    fun `batches queued by SDK 1_10 are uploaded under their own session`() {
        val d = Device()
        val legacySession = mapOf("sessionId" to "11111111-2222-4333-8444-555555555555", "anonymousId" to "anon-old",
            "customUserId" to "u-old", "deviceModel" to "Pixel")
        val legacyEvents = listOf(mapOf("type" to "TAP", "x" to 1.0, "y" to 2.0, "timestamp" to "2026-01-04T10:00:00.000Z"))
        val (core, _) = d.process(StartInputs(priorBatches = listOf(PriorBatch(null, legacySession, legacyEvents))))
        core.flush()
        val body = d.sessionBodies().first()
        assertEquals("11111111-2222-4333-8444-555555555555", body["sessionId"])
        assertEquals("anon-old", body["anonymousId"])
        assertEquals("u-old", body["customUserId"])
        @Suppress("UNCHECKED_CAST")
        val ev = (body["events"] as List<Map<String, Any?>>).single()
        assertEquals("TAP", ev["type"])
        assertNotNull(ev["eventId"])
    }

    @Test
    fun `calls before start are buffered, at most 200, oldest dropped`() {
        val d = Device()
        val core = UnilitixCore()
        repeat(250) { core.track("e$it", null) }
        assertFalse(core.isStarted)
        val deps = EngineDeps(d.clock, FakeScheduler(d.clock), InlineExecutor(), d.transport, d.network, FakeDevice, FakeRandom(emptyList()), d.store)
        core.start(Engine(d.config, deps))
        core.flush()
        @Suppress("UNCHECKED_CAST")
        val names = d.events().drop(1).map { (it["metadata"] as Map<String, Any?>)["name"] }
        assertEquals((50 until 250).map { "e$it" }, names)
    }
}
