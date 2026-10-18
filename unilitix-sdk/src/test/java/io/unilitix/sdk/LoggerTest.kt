package io.unilitix.sdk

import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoggerTest {

    private class Recorder : LogSink {
        val lines = mutableListOf<String>()
        override fun debug(tag: String, message: String) { lines.add("D $message") }
        override fun info(tag: String, message: String) { lines.add("I $message") }
        override fun warn(tag: String, message: String) { lines.add("W $message") }
        override fun error(tag: String, message: String, throwable: Throwable?) { lines.add("E $message") }
    }

    private val recorder = Recorder()
    private lateinit var previousSink: LogSink
    private var previousEnabled = false

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
    fun `debugLogging defaults to false regardless of BuildConfig`() {
        // Holds in testDebugUnitTest (BuildConfig.DEBUG = true) and
        // testReleaseUnitTest alike: the default no longer follows BuildConfig.
        assertFalse(UnilitixConfig.Builder().build().debugLogging)
    }

    @Test
    fun `debug lines are dropped when debugLogging is off, even in a debug build`() {
        Logger.configure(UnilitixConfig.Builder().build().debugLogging)
        Logger.d("hidden")
        Logger.i("hidden")
        assertTrue(recorder.lines.isEmpty())
    }

    @Test
    fun `debug lines are emitted when debugLogging is on`() {
        // The gate is only the runtime flag: no BuildConfig.DEBUG check, so the
        // same path runs in the published release AAR.
        Logger.configure(UnilitixConfig.Builder().apply { debugLogging = true }.build().debugLogging)
        Logger.d("shown")
        Logger.i("info")
        assertEquals(listOf("D shown", "I info"), recorder.lines)
    }

    @Test
    fun `warnings and errors are always emitted`() {
        Logger.configure(false)
        Logger.w("warn")
        Logger.e("err")
        assertEquals(listOf("W warn", "E err"), recorder.lines)
    }

    @Test
    fun `java fluent setter enables logging`() {
        assertTrue(UnilitixConfig.Builder().debugLogging(true).build().debugLogging)
    }

    @Test
    fun `key label never includes the secret part`() {
        val key = "ul_live_0ccc63c2-80cf-469f-964b-33eeaff6e800"
        val label = Logger.keyLabel(key)
        assertEquals("ul_live_…", label)
        assertFalse(label.contains("0ccc"))
        assertEquals("…", Logger.keyLabel("abcdef1234567890"))
        assertEquals("…", Logger.keyLabel("abcdefghijklmnop_qrs"))
    }
}
