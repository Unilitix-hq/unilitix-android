package io.unilitix.sdk

import io.unilitix.sdk.capture.AnrTrace
import io.unilitix.sdk.capture.NativeCrashReporter
import io.unilitix.sdk.capture.ProcessExit
import io.unilitix.sdk.capture.ProcessExits
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.core.KeyValueStore
import io.unilitix.sdk.core.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANR reporting (1.9.0, contract 1.3.0): the main thread read out of the
 * `REASON_ANR` thread dump, the ANR event shape, selection by the exit
 * watermark, and placement (consent; the session is not marked crashed).
 */
class AnrTraceTest {

    private class MemoryStore(val backing: MutableMap<String, Any> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = backing[key] as? String
        override fun getBoolean(key: String, default: Boolean): Boolean = backing[key] as? Boolean ?: default
        override fun putString(key: String, value: String) { backing[key] = value }
        override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    }

    private val killedAt = 1_760_700_000_000L
    private val launchedAt = killedAt + 120_000

    /** A trimmed but structurally faithful Android 14 ANR dump (`getTraceInputStream()`). */
    private fun dump(lineShift: Int = 0, sysTid: Int = 8123) = """
Subject: Input dispatching timed out (8f2a1b3 com.shop/com.shop.ui.CheckoutActivity (server) is not responding. Waited 5001ms for MotionEvent(deviceId=4, source=TOUCHSCREEN, action=DOWN))
RssHwmKb: 245324
RssKb: 239872

--- CriticalEventLog ---
capacity: 20
timestamp_ms: $killedAt

----- pid $sysTid at 2025-10-17 12:00:00.123456789+0100 -----
Cmd line: com.shop
Build fingerprint: 'google/raven/raven:14/UQ1A.240205.004/11269751:user/release-keys'
ABI: 'arm64'
Build type: optimized
suspend all histogram:	Sum: 1.234ms 99% C.I. 2us-412us Avg: 34.5us Max: 412us
DALVIK THREADS (24):
"main" prio=5 tid=1 Blocked
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a1b2c8 self=0xb400007a1c2d3e40
  | sysTid=$sysTid nice=-10 cgrp=top-app sched=0/0 handle=0x7b9e1f44f8
  | state=S schedstat=( 1067012345 120345678 1234 ) utm=85 stm=21 core=3 HZ=100
  | stack=0x7fd2b3a000-0x7fd2b3c000 stackSize=8188KB
  | held mutexes=
  at com.shop.cart.CartRepository.load(CartRepository.kt:${88 + lineShift})
  - waiting to lock <0x0b1c2d3e> (a java.lang.Object) held by thread 14
  at com.shop.ui.CheckoutActivity.onPay(CheckoutActivity.kt:${118 + lineShift})
  at com.shop.ui.CheckoutActivity${'$'}${'$'}ExternalSyntheticLambda0.onClick(D8${'$'}${'$'}SyntheticClass:0)
  at android.view.View.performClick(View.java:7506)
  at android.os.Handler.handleCallback(Handler.java:942)
  at android.os.Looper.loopOnce(Looper.java:201)
  at android.os.Looper.loop(Looper.java:288)
  at android.app.ActivityThread.main(ActivityThread.java:7918)
  at java.lang.reflect.Method.invoke(Native method)
  at com.android.internal.os.RuntimeInit${'$'}MethodAndArgsCaller.run(RuntimeInit.java:548)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:936)

"Signal Catcher" daemon prio=10 tid=6 Runnable
  | group="system" sCount=0 ucsCount=0 flags=0 obj=0x12c80258 self=0xb400007a1c2f1000
  native: #00 pc 00000000005b3f1c  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+108)
  (no managed stack frames)

"DefaultDispatcher-worker-1" daemon prio=5 tid=14 Sleeping
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x12c81000 self=0xb400007a1c2f2000
  at java.lang.Thread.sleep(Native method)
  - sleeping on <0x0c3d4e5f> (a java.lang.Object)
  at com.shop.cart.CartCache.refresh(CartCache.kt:40)
  - locked <0x0b1c2d3e> (a java.lang.Object)

----- end $sysTid -----
""".trimStart()

    private val expectedMain = """
"main" prio=5 tid=1 Blocked
  at com.shop.cart.CartRepository.load(CartRepository.kt:88)
  - waiting to lock <0x0b1c2d3e> (a java.lang.Object) held by thread 14
  at com.shop.ui.CheckoutActivity.onPay(CheckoutActivity.kt:118)
  at com.shop.ui.CheckoutActivity${'$'}${'$'}ExternalSyntheticLambda0.onClick(D8${'$'}${'$'}SyntheticClass:0)
  at android.view.View.performClick(View.java:7506)
  at android.os.Handler.handleCallback(Handler.java:942)
  at android.os.Looper.loopOnce(Looper.java:201)
  at android.os.Looper.loop(Looper.java:288)
  at android.app.ActivityThread.main(ActivityThread.java:7918)
  at java.lang.reflect.Method.invoke(Native method)
  at com.android.internal.os.RuntimeInit${'$'}MethodAndArgsCaller.run(RuntimeInit.java:548)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:936)
""".trim()

    private fun anrExit(
        ts: Long,
        dump: String? = dump(),
        description: String? = "user request after error: Input dispatching timed out",
        importance: Int? = 100,
        reads: IntArray? = null
    ) = ProcessExit(ProcessExits.REASON_ANR, ts, pid = 8123, description = description, importance = importance,
        readTrace = { reads?.let { it[0]++ }; dump?.toByteArray() })

    // ── Parsing the dump ─────────────────────────────────────────────

    @Test
    fun `main thread is read out of the dump without its property lines`() {
        assertEquals(expectedMain, AnrTrace.mainThread(dump()))
    }

    @Test
    fun `fingerprint groups by where the main thread was stuck, not by device details`() {
        val a = AnrTrace.mainThread(dump())!!
        val b = AnrTrace.mainThread(dump(lineShift = 7, sysTid = 9001))!!
        assertTrue("line numbers differ", a != b)
        assertEquals(Fingerprint.of("ANR", a), Fingerprint.of("ANR", b))
        // Same stack as a crash: a different issue.
        assertTrue(Fingerprint.of("ANR", a) != Fingerprint.of("java.lang.IllegalStateException", a))
        // Another thread's frames (the lock holder) do not leak in.
        assertFalse(a.contains("CartCache"))
    }

    @Test
    fun `no main thread, empty or garbage dump`() {
        assertNull(AnrTrace.mainThread(""))
        assertNull(AnrTrace.mainThread("\u0000\u0001 not a dump"))
        assertNull(AnrTrace.mainThread("\"mainly\" prio=5 tid=2 Runnable\n  at a.B.c(B.kt:1)"))
        // Main thread last in the dump, no trailing blank line.
        assertEquals("\"main\" prio=5 tid=1 Native\n  at a.B.c(B.kt:1)",
            AnrTrace.mainThread("x\n\"main\" prio=5 tid=1 Native\n  | sysTid=1\n  at a.B.c(B.kt:1)"))
    }

    @Test
    fun `main thread is capped on a line boundary`() {
        val frames = (1..2_000).joinToString("\n") { "  at com.shop.Deep.f$it(Deep.kt:$it)" }
        val stack = AnrTrace.mainThread("\"main\" prio=5 tid=1 Runnable\n$frames\n")!!
        assertTrue(stack.length <= AnrTrace.MAX_STACK)
        assertTrue(stack.endsWith(")"))
        assertTrue(stack.startsWith("\"main\" prio=5 tid=1 Runnable\n  at com.shop.Deep.f1(Deep.kt:1)"))
    }

    @Test
    fun `subject line is the message fallback`() {
        assertEquals(
            "Input dispatching timed out (8f2a1b3 com.shop/com.shop.ui.CheckoutActivity (server) is not responding. " +
                "Waited 5001ms for MotionEvent(deviceId=4, source=TOUCHSCREEN, action=DOWN))",
            AnrTrace.subject(dump())
        )
        assertEquals("Input dispatching timed out (8f2a1b3",
            AnrTrace.fromExit(anrExit(killedAt, description = null)).exceptionMessage.take(36))
        assertEquals(AnrTrace.DEFAULT_MESSAGE, AnrTrace.fromExit(anrExit(killedAt, dump = null, description = " ")).exceptionMessage)
    }

    @Test
    fun `unreadable trace keeps the report with an empty stack`() {
        val throwing = ProcessExit(ProcessExits.REASON_ANR, killedAt, 1, "Input dispatching timed out",
            readTrace = { throw java.io.IOException("gone") })
        val anr = AnrTrace.fromExit(throwing)
        assertEquals("", anr.stackTrace)
        assertEquals("Input dispatching timed out", anr.exceptionMessage)
        assertEquals(Fingerprint.of("ANR", ""), anr.fingerprint)
    }

    // ── Event shape ─────────────────────────────────────────────────

    @Test
    fun `ANR event shape`() {
        val anr = AnrTrace.fromExit(anrExit(killedAt))
        val event = AnrTrace.toEvent(anr, launchedAt) { "t=$it" }
        assertEquals("ANR", event["type"])
        assertEquals("t=$killedAt", event["timestamp"])
        assertEquals("ANR", event["exceptionType"])
        assertEquals("user request after error: Input dispatching timed out", event["exceptionMessage"])
        assertEquals(expectedMain, event["stackTrace"])
        assertFalse("an ANR is not a crash: no fatal flag", event.containsKey("fatal"))
        @Suppress("UNCHECKED_CAST")
        val meta = event["metadata"] as Map<String, Any?>
        assertEquals(Fingerprint.of("ANR", expectedMain), meta["fingerprint"])
        assertEquals("android", meta["platform"])
        assertEquals(100, meta["importance"])
        assertEquals(true, meta["foreground"])

        val background = AnrTrace.toEvent(AnrTrace.fromExit(anrExit(killedAt, importance = 400)), launchedAt) { "$it" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(false, (background["metadata"] as Map<String, Any?>)["foreground"])
        val unknown = AnrTrace.toEvent(AnrTrace.fromExit(anrExit(0, importance = null)), launchedAt) { "$it" }
        assertEquals("unknown time: now", "$launchedAt", unknown["timestamp"])
        @Suppress("UNCHECKED_CAST")
        assertFalse((unknown["metadata"] as Map<String, Any?>).containsKey("foreground"))
    }

    // ── Selection and placement ──────────────────────────────────────

    @Test
    fun `ANRs are selected once by the exit watermark, newest five, dumps read only for those`() {
        val reads = intArrayOf(0)
        val exits = (1..8).map { anrExit(killedAt + it, reads = reads) }
        val kv = MemoryStore(mutableMapOf(ProcessExits.KEY_WATERMARK to killedAt.toString()))
        val scan = ProcessExits.scan(kv, exits, launchedAt)
        assertEquals(ProcessExits.MAX_ANRS_REPORTED, scan.anrs.size)
        assertEquals((4..8).map { killedAt + it }, scan.anrs.map { it.timestampMs })
        assertEquals(ProcessExits.MAX_ANRS_REPORTED, reads[0])
        assertTrue(scan.crashes.isEmpty())
        // Next launch: already seen.
        assertTrue(ProcessExits.scan(kv, exits, launchedAt + 1).anrs.isEmpty())
    }

    @Test
    fun `first launch with ANR reporting reports no old ANRs`() {
        val scan = ProcessExits.scan(MemoryStore(), listOf(anrExit(killedAt)), launchedAt)
        assertTrue(scan.anrs.isEmpty())
    }

    @Test
    fun `ANR goes to the session that was running, consent pending holds and denied drops`() {
        val h = SessionHistory(MemoryStore())
        h.recordStart("old", killedAt - 3_600_000)
        h.recordStart("frozen", killedAt - 60_000)
        h.recordBatch("frozen", killedAt - 60_000, """{"sessionId":"frozen","crashed":false}""", killedAt - 30_000)
        h.recordStart("current", launchedAt)
        val anr = AnrTrace.fromExit(anrExit(killedAt))
        val reporter = NativeCrashReporter(h)

        val placed = reporter.placeAnrs(listOf(anr), UnilitixConsent.GRANTED, "current")
        assertEquals(1, placed.size)
        assertEquals("frozen", placed[0].session!!.id)
        assertEquals("the session ends when the process was killed", killedAt, SessionHistory.endedAt(placed[0].session!!, killedAt))

        assertEquals(1, reporter.placeAnrs(listOf(anr), UnilitixConsent.PENDING, "current").size)
        assertTrue(reporter.placeAnrs(listOf(anr), UnilitixConsent.DENIED, "current").isEmpty())

        val none = NativeCrashReporter(SessionHistory(MemoryStore())).placeAnrs(listOf(anr), UnilitixConsent.GRANTED, "current")
        assertNull("no stored session: the current one", none[0].session)
    }
}
