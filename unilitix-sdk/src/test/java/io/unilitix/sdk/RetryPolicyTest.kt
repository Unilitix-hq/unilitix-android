package io.unilitix.sdk

import io.unilitix.sdk.network.ApiResult
import io.unilitix.sdk.network.RetryPolicy
import io.unilitix.sdk.network.UploadGate
import io.unilitix.sdk.network.UploadOutcome
import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class RetryPolicyTest {

    private class Recorder : LogSink {
        val lines = mutableListOf<String>()
        override fun debug(tag: String, message: String) { lines.add("D $message") }
        override fun info(tag: String, message: String) { lines.add("I $message") }
        override fun warn(tag: String, message: String) { lines.add("W $message") }
        override fun error(tag: String, message: String, throwable: Throwable?) { lines.add("E $message") }
    }

    /** Random whose nextDouble() is always [v]. */
    private class FixedRandom(private val v: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = v
    }

    private val recorder = Recorder()
    private lateinit var previousSink: LogSink
    private var previousEnabled = false
    private var now = 0L

    @Before
    fun setUp() {
        previousSink = Logger.sink
        previousEnabled = Logger.debugEnabled
        Logger.sink = recorder
    }

    @After
    fun tearDown() {
        Logger.sink = previousSink
        Logger.debugEnabled = previousEnabled
    }

    @Test
    fun `backoff doubles from 1s and caps at 5 minutes`() {
        val noJitter = FixedRandom(0.0)
        assertEquals(1_000L, RetryPolicy.backoffMs(0, noJitter))
        assertEquals(2_000L, RetryPolicy.backoffMs(1, noJitter))
        assertEquals(4_000L, RetryPolicy.backoffMs(2, noJitter))
        assertEquals(256_000L, RetryPolicy.backoffMs(8, noJitter))
        assertEquals(300_000L, RetryPolicy.backoffMs(9, noJitter))
        assertEquals(300_000L, RetryPolicy.backoffMs(1_000, noJitter))
    }

    @Test
    fun `jitter adds at most 20 percent`() {
        for (n in 0..12) {
            val base = minOf(1_000L shl n, 300_000L)
            assertTrue(RetryPolicy.backoffMs(n, FixedRandom(0.999999)) <= base + base / 5)
            repeat(200) {
                val d = RetryPolicy.backoffMs(n)
                assertTrue("n=$n d=$d", d >= base && d <= base + base / 5)
            }
        }
    }

    @Test
    fun `Retry-After replaces backoff and is clamped to 1-60 s`() {
        assertEquals(30_000L, RetryPolicy.delayFor(7, 30))
        assertEquals(1_000L, RetryPolicy.delayFor(0, 0))
        assertEquals(1_000L, RetryPolicy.delayFor(0, -5))
        assertEquals(60_000L, RetryPolicy.delayFor(0, 3_600))
        assertEquals(12L, RetryPolicy.parseRetryAfter(" 12 "))
        assertEquals(null, RetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(null, RetryPolicy.parseRetryAfter(null))
    }

    @Test
    fun `status codes are classified per the contract`() {
        fun outcome(code: Int) = RetryPolicy.classify(ApiResult.failure(code, null))
        assertEquals(UploadOutcome.RETRY_LATER, outcome(408))
        assertEquals(UploadOutcome.RETRY_LATER, outcome(429))
        assertEquals(UploadOutcome.RETRY_LATER, outcome(500))
        assertEquals(UploadOutcome.RETRY_LATER, outcome(503))
        assertEquals(UploadOutcome.AUTH_REJECTED, outcome(401))
        assertEquals(UploadOutcome.AUTH_REJECTED, outcome(403))
        assertEquals(UploadOutcome.DROPPED, outcome(400))
        assertEquals(UploadOutcome.DROPPED, outcome(413))
        assertEquals(UploadOutcome.DROPPED, outcome(422))
        assertEquals(UploadOutcome.RETRY_LATER, RetryPolicy.classify(ApiResult.NetworkError(IOException("x"))))
        assertEquals(UploadOutcome.SENT, RetryPolicy.classify(ApiResult.Success("s")))
        assertEquals(ApiResult.RateLimit(429, 7), ApiResult.failure(429, "7"))
        assertEquals(ApiResult.ServerError(503, 2), ApiResult.failure(503, "2"))
    }

    @Test
    fun `retryable failure opens a growing backoff window and success closes it`() {
        val gate = UploadGate(clock = { now }, random = FixedRandom(0.0))
        assertFalse(gate.isPaused)

        assertEquals(UploadOutcome.RETRY_LATER, gate.note(ApiResult.ServerError(500), "batch"))
        assertEquals(1_000L, gate.remainingBackoffMs())
        assertTrue(gate.isPaused)
        now += 1_000
        assertFalse(gate.isPaused)

        gate.note(ApiResult.NetworkError(IOException("offline")), "batch")
        assertEquals(2_000L, gate.remainingBackoffMs())
        now += 2_000
        gate.note(ApiResult.RateLimit(429, null), "batch")
        assertEquals(4_000L, gate.remainingBackoffMs())
        assertEquals(3, gate.consecutiveFailures)

        now += 4_000
        assertEquals(UploadOutcome.SENT, gate.note(ApiResult.Success(), "batch"))
        assertEquals(0, gate.consecutiveFailures)
        gate.note(ApiResult.ServerError(502), "batch")
        assertEquals("reset to the first step after a success", 1_000L, gate.remainingBackoffMs())
    }

    @Test
    fun `Retry-After sets the window`() {
        val gate = UploadGate(clock = { now }, random = FixedRandom(0.0))
        gate.note(ApiResult.failure(429, "120"), "batch")
        assertEquals(60_000L, gate.remainingBackoffMs())
        now += 60_000
        gate.note(ApiResult.failure(503, "3"), "batch")
        assertEquals(3_000L, gate.remainingBackoffMs())
    }

    @Test
    fun `other 4xx drops the batch without backoff`() {
        val gate = UploadGate(clock = { now })
        assertEquals(UploadOutcome.DROPPED, gate.note(ApiResult.failure(400, null, "bad"), "batch"))
        assertFalse(gate.isPaused)
        assertEquals(0, gate.consecutiveFailures)
    }

    @Test
    fun `401 and 403 latch uploads off, keep data, and warn once without the key`() {
        val gate = UploadGate(clock = { now })
        assertEquals(UploadOutcome.AUTH_REJECTED, gate.note(ApiResult.failure(401, null), "batch"))
        assertTrue(gate.authRejected)
        assertTrue(gate.isPaused)
        // The latch is not a backoff window: it never expires on its own.
        now += 24L * 3_600_000
        assertTrue(gate.isPaused)
        // A later success elsewhere does not clear it either.
        gate.note(ApiResult.failure(403, null), "screenshots")
        gate.note(ApiResult.Success(), "batch")
        assertTrue(gate.authRejected)

        val warnings = recorder.lines.filter { it.startsWith("W ") && "rejected" in it }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("stays on the device"))
        assertFalse(recorder.lines.any { "ul_live" in it || "x-unilitix-key" in it })
    }

    @Test
    fun `backoff logging is verbose only`() {
        Logger.debugEnabled = false
        UploadGate(clock = { now }).note(ApiResult.ServerError(500), "batch")
        assertTrue(recorder.lines.isEmpty())
    }
}
