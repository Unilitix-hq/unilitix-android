package io.unilitix.sdk

import io.unilitix.sdk.core.GuardedInit
import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 1.10.2: the exception barrier around Unilitix.init. */
class GuardedInitTest {

    private class Recorder : LogSink {
        val warnings = mutableListOf<String>()
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String) { warnings.add(message) }
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    private class Sdk {
        var started = false
        var tornDown = false
    }

    private val recorder = Recorder()
    private lateinit var previousSink: LogSink

    @Before
    fun setUp() {
        previousSink = Logger.sink
        Logger.sink = recorder
    }

    @After
    fun tearDown() {
        Logger.sink = previousSink
    }

    @Test
    fun `a successful start returns the instance and does not tear down`() {
        val sdk = Sdk()
        val result = GuardedInit.run(create = { sdk }, start = { it.started = true }, teardown = { it.tornDown = true })
        assertSame(sdk, result)
        assertTrue(sdk.started)
        assertEquals(false, sdk.tornDown)
        assertTrue(recorder.warnings.isEmpty())
    }

    @Test
    fun `a throwing constructor returns null and logs one warning`() {
        val result = GuardedInit.run<Sdk>(
            create = { throw IllegalStateException("keystore") },
            start = { },
            teardown = { }
        )
        assertNull(result)
        assertEquals(1, recorder.warnings.size)
        assertTrue(recorder.warnings[0].contains("IllegalStateException"))
    }

    @Test
    fun `a throwing start tears down and returns null`() {
        val sdk = Sdk()
        val result = GuardedInit.run(
            create = { sdk },
            start = { throw SecurityException("registerDefaultNetworkCallback") },
            teardown = { it.tornDown = true }
        )
        assertNull(result)
        assertTrue(sdk.tornDown)
        assertTrue(recorder.warnings.single().contains("SecurityException"))
    }

    @Test
    fun `errors, not only exceptions, are contained`() {
        val result = GuardedInit.run(
            create = { Sdk() },
            start = { throw OutOfMemoryError("test") },
            teardown = { throw IllegalStateException("teardown also fails") }
        )
        assertNull(result)
    }

    @Test
    fun `a failed init can be retried`() {
        var attempts = 0
        val sdk = Sdk()
        fun attempt() = GuardedInit.run(
            create = { sdk },
            start = { attempts++; if (attempts == 1) throw RuntimeException("first try") },
            teardown = { }
        )
        assertNull(attempt())
        assertSame(sdk, attempt())
        assertEquals(2, attempts)
    }
}
