package io.unilitix.sdk

import io.unilitix.sdk.capture.NativeCrashFile
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.capture.NativeCrashReporter
import io.unilitix.sdk.capture.NativeSignalHandler
import io.unilitix.sdk.capture.ProcessExit
import io.unilitix.sdk.capture.ProcessExits
import io.unilitix.sdk.capture.TombstoneSignal
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.core.KeyValueStore
import io.unilitix.sdk.core.SessionHistory
import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Native crash capture (1.7.0), matched against UnilitixCore (iOS) 0.5.0
 * NativeCrashTests: record format, CRASH shape and fingerprint, attribution
 * with 1 s slack, consent, exactly-once consumption, and the API 30+
 * exit-info selection.
 */
class NativeCrashTest {

    private class MemoryStore(val backing: MutableMap<String, Any> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = backing[key] as? String
        override fun getBoolean(key: String, default: Boolean): Boolean = backing[key] as? Boolean ?: default
        override fun putString(key: String, value: String) { backing[key] = value }
        override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    }

    private object Silent : LogSink {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    private lateinit var previousSink: LogSink

    @Before
    fun setUp() {
        previousSink = Logger.sink
        Logger.sink = Silent
        NativeSignalHandler.resetForTests()
    }

    @After
    fun tearDown() {
        Logger.sink = previousSink
        NativeSignalHandler.resetForTests()
    }

    private val crashedAt = 1_760_700_000_000L
    private val launchedAt = crashedAt + 60_000L

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    /** Exactly what libunilitix-native.so writes. */
    private fun signalRecord(signal: Int, ts: Long = crashedAt) =
        """{"platform":"android","exceptionType":"SIGNAL","signal":$signal,"timestamp":$ts}"""

    // ── Record → CRASH event ─────────────────────────────────────────

    @Test
    fun `signal record becomes a fatal CRASH like UnilitixCore reports it`() {
        val crash = NativeCrashRecord.parse(signalRecord(11))!!
        val e = NativeCrashRecord.toEvent(crash, launchedAt, ::iso)
        assertEquals("CRASH", e["type"])
        assertEquals(true, e["fatal"])
        assertEquals("SIGNAL", e["exceptionType"])
        assertEquals("Fatal signal 11 (SIGSEGV)", e["exceptionMessage"])
        assertEquals("SIGNAL: Fatal signal 11 (SIGSEGV)", e["title"])
        assertEquals("", e["stackTrace"])
        assertEquals("the time of death, not the next launch", iso(crashedAt), e["timestamp"])
        @Suppress("UNCHECKED_CAST")
        val metadata = e["metadata"] as Map<String, Any?>
        // Fingerprint.of("SIGNAL:11", null): the iOS / Dart vector.
        assertEquals("8f1fc15bfa06be00", metadata["fingerprint"])
        assertEquals(11, metadata["signal"])
        assertEquals("android", metadata["platform"])
    }

    @Test
    fun `fingerprint is SIGNAL colon n`() {
        assertEquals("8f1fc15bfa06be00", NativeCrashRecord.fingerprint("SIGNAL", 11, null))
        assertEquals(Fingerprint.of("SIGNAL:6", null), NativeCrashRecord.fromSignal(6, crashedAt).fingerprint)
        assertEquals("8f1fc15bfa06be00", NativeCrashRecord.fromSignal(11, crashedAt).fingerprint)
    }

    @Test
    fun `each caught signal has its name`() {
        assertEquals(
            listOf("SIGABRT", "SIGBUS", "SIGFPE", "SIGILL", "SIGSEGV", "SIGTRAP"),
            listOf(6, 7, 8, 4, 11, 5).map { NativeCrashRecord.signalName(it) }
        )
        assertNull(NativeCrashRecord.signalName(0))
        assertEquals("Fatal signal 31", NativeCrashRecord.signalMessage(31))
    }

    @Test
    fun `unknown signal is keyed SIGNAL with no signal metadata`() {
        val crash = NativeCrashRecord.fromSignal(null, crashedAt)
        val e = NativeCrashRecord.toEvent(crash, launchedAt, ::iso)
        assertEquals("SIGNAL", e["title"])
        assertEquals("", e["exceptionMessage"])
        @Suppress("UNCHECKED_CAST")
        val metadata = e["metadata"] as Map<String, Any?>
        assertEquals(Fingerprint.of("SIGNAL", null), metadata["fingerprint"])
        assertFalse(metadata.containsKey("signal"))
    }

    @Test
    fun `record without timestamp uses now`() {
        val crash = NativeCrashRecord.parse("""{"exceptionType":"SIGNAL","signal":6,"timestamp":0}""")!!
        assertNull(crash.timestampMs)
        assertEquals(iso(launchedAt), NativeCrashRecord.toEvent(crash, launchedAt, ::iso)["timestamp"])
        assertEquals("android", crash.platform)
    }

    @Test
    fun `record without type or not JSON is rejected`() {
        assertNull(NativeCrashRecord.parse("""{"signal":11,"timestamp":1}"""))
        assertNull(NativeCrashRecord.parse("not json"))
        assertNull(NativeCrashRecord.parse("""{"exceptionType":"SIGNAL","signal":11,"timest"""))
        assertNull(NativeCrashRecord.parse("[1,2]"))
    }

    @Test
    fun `exception record from another SDK keeps its message stack and fingerprint`() {
        val text = """{"exceptionMessage":"bad \"arg\"\n","exceptionType":"NSInvalidArgumentException",""" +
            """"platform":"ios","stackTrace":"0 CoreFoundation","timestamp":$crashedAt,"fingerprint":"0123456789abcdef"}"""
        val crash = NativeCrashRecord.parse(text)!!
        assertEquals("NSInvalidArgumentException", crash.exceptionType)
        assertEquals("bad \"arg\"\n", crash.exceptionMessage)
        assertEquals("0 CoreFoundation", crash.stackTrace)
        assertEquals("0123456789abcdef", crash.fingerprint)
        assertEquals("ios", crash.platform)
        assertNull(crash.signal)
    }

    // ── The record file ──────────────────────────────────────────────

    @Test
    fun `record file is consumed exactly once`() {
        val dir = Files.createTempDirectory("unilitix").toFile()
        try {
            val file = NativeCrashFile.locate(dir)
            assertEquals(File(File(dir, "unilitix"), "native_crash.json"), file)
            assertNull(NativeCrashFile.consume(file))
            file.parentFile!!.mkdirs()
            file.writeText(signalRecord(11))
            assertEquals(signalRecord(11), NativeCrashFile.consume(file))
            assertFalse(file.exists())
            assertNull(NativeCrashFile.consume(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── API 30+: ApplicationExitInfo ─────────────────────────────────

    private fun exit(reason: Int, ts: Long, description: String? = null, trace: ByteArray? = null) =
        ProcessExit(reason, ts, pid = 1234, description = description, readTrace = { trace })

    @Test
    fun `first run sets the watermark to now and reports nothing`() {
        val kv = MemoryStore()
        val old = listOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, "Fatal signal 11"))
        val scan = ProcessExits.scan(kv, old, launchedAt)
        assertTrue(scan.crashes.isEmpty())
        assertEquals(launchedAt.toString(), kv.getString(ProcessExits.KEY_WATERMARK))

        // Next launch: only exits after the watermark count.
        val next = old + exit(ProcessExits.REASON_CRASH_NATIVE, launchedAt + 5_000, "Fatal signal 6")
        val second = ProcessExits.scan(kv, next, launchedAt + 60_000)
        assertEquals(listOf(6), second.crashes.map { it.signal })
        assertEquals((launchedAt + 5_000).toString(), kv.getString(ProcessExits.KEY_WATERMARK))

        // And the launch after that: nothing new.
        assertTrue(ProcessExits.scan(kv, next, launchedAt + 120_000).crashes.isEmpty())
    }

    @Test
    fun `native crashes are reported as crashes, ANRs separately, JVM crashes not at all`() {
        val exits = listOf(
            exit(ProcessExits.REASON_CRASH, crashedAt + 1, "crash"),
            exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt + 2, "Fatal signal 11 (SIGSEGV)"),
            exit(ProcessExits.REASON_ANR, crashedAt + 3, "Input dispatching timed out"),
            exit(1 /* EXIT_SELF */, crashedAt + 4),
            exit(3 /* LOW_MEMORY */, crashedAt + 5)
        )
        val scan = ProcessExits.select(exits, watermark = crashedAt, nowMs = launchedAt)
        assertEquals(1, scan.crashes.size)
        assertEquals(11, scan.crashes[0].signal)
        assertEquals(crashedAt + 2, scan.crashes[0].timestampMs)
        assertEquals("8f1fc15bfa06be00", scan.crashes[0].fingerprint)
        assertEquals("ANRs are reported as ANRs (1.9.0), not crashes", 1, scan.anrs.size)
        assertEquals(crashedAt + 3, scan.anrs[0].timestampMs)
        assertEquals("Input dispatching timed out", scan.anrs[0].exceptionMessage)
        assertEquals(crashedAt + 5, scan.watermark)
    }

    @Test
    fun `a crash loop reports at most five, the newest`() {
        val exits = (1..12).map { exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt + it, "Fatal signal 11") }
        val scan = ProcessExits.select(exits, crashedAt, launchedAt)
        assertEquals(ProcessExits.MAX_REPORTED, scan.crashes.size)
        assertEquals((8..12).map { crashedAt + it }, scan.crashes.map { it.timestampMs })
        assertEquals(crashedAt + 12, scan.watermark)
    }

    @Test
    fun `watermark never moves backwards`() {
        val scan = ProcessExits.select(listOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt - 10)), crashedAt, launchedAt)
        assertTrue(scan.crashes.isEmpty())
        assertEquals(crashedAt, scan.watermark)
    }

    // Tombstone protobuf: hand-built wire bytes.

    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { write(x.toInt()); return }
            write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }
    private fun ByteArrayOutputStream.tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun ByteArrayOutputStream.bytesField(field: Int, payload: ByteArray) {
        tag(field, 2); varint(payload.size.toLong()); write(payload)
    }
    private fun ByteArrayOutputStream.varintField(field: Int, v: Long) { tag(field, 0); varint(v) }

    private fun signalInfo(number: Int): ByteArray = ByteArrayOutputStream().apply {
        varintField(1, number.toLong())         // int32 number
        bytesField(2, "SIGSEGV".toByteArray())  // string name
        varintField(3, 1)                       // int32 code
        bytesField(4, "SEGV_MAPERR".toByteArray())
        varintField(8, 1)                       // bool has_fault_address
        tag(9, 0); varint(0xdeadbeefL)          // uint64 fault_address
    }.toByteArray()

    private fun tombstone(signal: Int?, tail: Boolean = true): ByteArray = ByteArrayOutputStream().apply {
        varintField(1, 3)                                            // Architecture arch = ARM64
        bytesField(2, "google/raven/raven:14/UQ1A".toByteArray())  // build_fingerprint
        bytesField(4, "2025-10-17 12:00:00".toByteArray())          // timestamp
        varintField(5, 4321); varintField(6, 4321); varintField(7, 10123)
        bytesField(9, "com.example.app".toByteArray())              // command_line
        tag(11, 1); write(ByteArray(8))                              // a fixed64, skipped
        tag(12, 5); write(ByteArray(4))                              // a fixed32, skipped
        if (signal != null) bytesField(10, signalInfo(signal))       // Signal signal_info
        if (tail) bytesField(16, ByteArray(300) { 7 })               // threads etc.
    }.toByteArray()

    @Test
    fun `tombstone signal is read from the protobuf`() {
        assertEquals(11, TombstoneSignal.parse(tombstone(11)))
        assertEquals(6, TombstoneSignal.parse(tombstone(6)))
        assertNull(TombstoneSignal.parse(tombstone(null)))
        assertNull(TombstoneSignal.parse(ByteArray(0)))
        assertNull(TombstoneSignal.parse("plain text tombstone\n".toByteArray()))
    }

    @Test
    fun `truncated tombstone still yields the signal read before the cut`() {
        val full = tombstone(7)
        // Cut inside the trailing field: signal_info is complete.
        assertEquals(7, TombstoneSignal.parse(full.copyOf(full.size - 100)))
        // Cut before signal_info: nothing.
        assertNull(TombstoneSignal.parse(full.copyOf(20)))
    }

    @Test
    fun `tombstone wins over the description, description is the fallback`() {
        val withTrace = exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, "Fatal signal 6 (SIGABRT)", tombstone(11))
        assertEquals(11, ProcessExits.signalOf(withTrace))
        val noTrace = exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, "Fatal signal 6 (SIGABRT), code -1")
        assertEquals(6, ProcessExits.signalOf(noTrace))
        val throwing = ProcessExit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, 1, "SIGBUS at 0x0", readTrace = { throw java.io.IOException("gone") })
        assertEquals(7, ProcessExits.signalOf(throwing))
        assertNull(ProcessExits.signalOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, null)))
        assertNull(ProcessExits.signalOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt, "crash")))
    }

    @Test
    fun `description parsing`() {
        assertEquals(11, ProcessExits.signalFromDescription("Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"))
        assertEquals(6, ProcessExits.signalFromDescription("signal 6"))
        assertEquals(31, ProcessExits.signalFromDescription("seccomp prevented call: SIGSYS"))
        assertEquals(8, ProcessExits.signalFromDescription("SIGFPE"))
        assertNull(ProcessExits.signalFromDescription(""))
        assertNull(ProcessExits.signalFromDescription("signal 999"))
    }

    @Test
    fun `native crash without a readable signal reports as unknown`() {
        val scan = ProcessExits.select(listOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt + 1, null)), crashedAt, launchedAt)
        assertEquals(1, scan.crashes.size)
        assertNull(scan.crashes[0].signal)
        assertEquals(Fingerprint.of("SIGNAL", null), scan.crashes[0].fingerprint)
    }

    // ── Attribution and consent ──────────────────────────────────────

    private fun history(): SessionHistory = SessionHistory(MemoryStore())

    @Test
    fun `crash is attributed to the session that was running`() {
        val h = history()
        h.recordStart("old", crashedAt - 3_600_000)
        h.recordStart("crashed", crashedAt - 60_000)
        h.recordBatch("crashed", crashedAt - 60_000, """{"sessionId":"crashed"}""", crashedAt - 30_000)
        h.recordStart("current", launchedAt)
        val crash = NativeCrashRecord.parse(signalRecord(11))!!
        val placed = NativeCrashReporter(h).place(listOf(crash), UnilitixConsent.GRANTED, currentSessionId = "current")
        assertEquals(1, placed.size)
        val target = placed[0].session!!
        assertEquals("crashed", target.id)
        assertEquals("""{"sessionId":"crashed"}""", target.sessionJson)
        assertEquals("the session's duration ends at the crash", crashedAt, SessionHistory.endedAt(target, crashedAt))
    }

    @Test
    fun `session started within the second of the crash still matches`() {
        val h = history()
        h.recordStart("before", crashedAt - 10_000)
        h.recordStart("same-second", crashedAt + 999)
        h.recordStart("after", crashedAt + 1_001)
        assertEquals("same-second", h.sessionAt(crashedAt)!!.id)
    }

    @Test
    fun `no stored session reports the crash in the current one`() {
        val h = history()
        h.recordStart("current", launchedAt)
        val crash = NativeCrashRecord.parse(signalRecord(11))!!
        val placed = NativeCrashReporter(h).place(listOf(crash), UnilitixConsent.GRANTED, "current")
        assertEquals(1, placed.size)
        assertNull(placed[0].session)
    }

    @Test
    fun `crash without timestamp goes to the latest earlier session`() {
        val h = history()
        h.recordStart("a", crashedAt - 10_000)
        h.recordStart("b", crashedAt - 5_000)
        h.recordStart("current", launchedAt)
        assertEquals("b", h.sessionAt(null, excludeId = "current")!!.id)
    }

    @Test
    fun `pending consent holds the crash on the device, denied drops it`() {
        val h = history()
        h.recordStart("crashed", crashedAt - 1_000)
        val crash = NativeCrashRecord.parse(signalRecord(11))!!
        val reporter = NativeCrashReporter(h)
        assertEquals(1, reporter.place(listOf(crash), UnilitixConsent.PENDING, null).size)
        assertTrue(reporter.place(listOf(crash), UnilitixConsent.DENIED, null).isEmpty())
    }

    @Test
    fun `denied consent still advances the watermark so the crash is never reported later`() {
        val kv = MemoryStore(mutableMapOf(ProcessExits.KEY_WATERMARK to crashedAt.toString()))
        val exits = listOf(exit(ProcessExits.REASON_CRASH_NATIVE, crashedAt + 10, "Fatal signal 11"))
        // The caller scans first (always), then drops on DENIED.
        assertEquals(1, ProcessExits.scan(kv, exits, launchedAt).crashes.size)
        assertTrue(ProcessExits.scan(kv, exits, launchedAt + 1).crashes.isEmpty())
    }

    @Test
    fun `session history keeps the newest sessions and survives a relaunch`() {
        val kv = MemoryStore()
        val h = SessionHistory(kv, maxEntries = 3)
        (1..5).forEach { h.recordStart("s$it", it * 1_000L) }
        // JSON escapes tabs and newlines, so a record never breaks the line format.
        h.recordBatch("s5", 5_000, """{"a":"tab\tand\nnewline"}""", 6_000)
        val relaunched = SessionHistory(kv, maxEntries = 3)
        assertEquals(listOf("s3", "s4", "s5"), relaunched.entries().map { it.id })
        assertEquals(6_000L, relaunched.entries().last().lastSeenAt)
        assertNotNull(relaunched.entries().last().sessionJson)
        // A later start record keeps the stored JSON.
        relaunched.recordStart("s5", 5_000)
        assertNotNull(relaunched.entries().last().sessionJson)
        relaunched.clear()
        assertTrue(relaunched.entries().isEmpty())
    }

    @Test
    fun `crashed session ends at its last sign of life if that is later than the crash`() {
        val e = SessionHistory.Entry("s", startedAt = 1_000, lastSeenAt = 9_000, sessionJson = null)
        assertEquals(9_000, SessionHistory.endedAt(e, 5_000))
        assertEquals(12_000, SessionHistory.endedAt(e, 12_000))
        assertEquals(9_000, SessionHistory.endedAt(e, null))
    }

    // ── Signal handler install and the JVM-exception suppression flag ─

    @Test
    fun `handler is not installed on API 30 and later`() {
        var loaded = false
        val file = File(Files.createTempDirectory("unilitix").toFile(), "unilitix/native_crash.json")
        assertFalse(NativeSignalHandler.install(file, sdkInt = 30, loadLibrary = { loaded = true }, nativeInstaller = { true }))
        assertFalse(loaded)
        assertFalse(NativeSignalHandler.isInstalled)
    }

    @Test
    fun `missing library is logged and the SDK carries on`() {
        val file = File(Files.createTempDirectory("unilitix").toFile(), "unilitix/native_crash.json")
        val ok = NativeSignalHandler.install(
            file, sdkInt = 29,
            loadLibrary = { throw UnsatisfiedLinkError("dlopen failed: library \"libunilitix-native.so\" not found") },
            nativeInstaller = { error("not reached") }
        )
        assertFalse(ok)
        assertFalse(NativeSignalHandler.isInstalled)
    }

    @Test
    fun `handler installs on API 29 with the record path`() {
        val file = File(Files.createTempDirectory("unilitix").toFile(), "unilitix/native_crash.json")
        var path: String? = null
        assertTrue(NativeSignalHandler.install(file, sdkInt = 29, loadLibrary = {}, nativeInstaller = { path = it; true }))
        assertEquals(file.absolutePath, path)
        assertTrue("directory created for the handler's open()", file.parentFile!!.isDirectory)
    }

    @Test
    fun `JVM exception marks the native side handled so the following abort is not reported`() {
        val file = File(Files.createTempDirectory("unilitix").toFile(), "unilitix/native_crash.json")
        NativeSignalHandler.install(file, sdkInt = 29, loadLibrary = {}, nativeInstaller = { true })
        var nativeMarked = 0
        NativeSignalHandler.markExceptionHandled(nativeMarker = { nativeMarked++ })
        assertTrue(NativeSignalHandler.exceptionHandled)
        assertEquals(1, nativeMarked)
    }

    @Test
    fun `marking handled without the library is a safe no-op`() {
        var nativeMarked = 0
        NativeSignalHandler.markExceptionHandled(nativeMarker = { nativeMarked++ })
        assertTrue(NativeSignalHandler.exceptionHandled)
        assertEquals("never calls into an unloaded library", 0, nativeMarked)
        // The real JNI path is skipped too (no UnsatisfiedLinkError).
        NativeSignalHandler.markExceptionHandled()
    }

    // ── coordination with the Flutter plugin (1.8.0) ─────────────────────

    @Test
    fun `native crash capture is not active before init`() {
        assertFalse(Unilitix.isInitialized)
        assertFalse(Unilitix.nativeCrashCaptureActive())
    }

    @Test
    fun `nativeCrashCaptureActive is a public static method the plugin can find by name`() {
        // The Flutter plugin looks it up with reflection (no compile-time
        // dependency): class name, method name, no parameters, static, boolean.
        val m = Class.forName("io.unilitix.sdk.Unilitix").getMethod("nativeCrashCaptureActive")
        assertTrue(java.lang.reflect.Modifier.isStatic(m.modifiers))
        assertEquals(java.lang.Boolean.TYPE, m.returnType)
        assertEquals(false, m.invoke(null))
    }
}
