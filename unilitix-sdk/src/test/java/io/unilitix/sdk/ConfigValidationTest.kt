package io.unilitix.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.10.2: out-of-range config values are clamped by build(), never thrown on later. */
class ConfigValidationTest {

    private fun build(block: UnilitixConfig.Builder.() -> Unit) =
        UnilitixConfig.Builder().apply(block).build()

    @Test
    fun `defaults are unchanged`() {
        val c = build { }
        assertEquals(30, c.flushIntervalSeconds)
        assertEquals(100, c.flushBatchSize)
        assertEquals(1000, c.maxOfflineEvents)
        assertEquals(1.0, c.sampleRate, 0.0)
        assertEquals(30, c.screenshotQuality)
        assertEquals(480, c.screenshotMaxWidth)
    }

    @Test
    fun `zero or negative flush interval is clamped so scheduleAtFixedRate cannot throw`() {
        assertEquals(UnilitixConfig.MIN_FLUSH_INTERVAL_SECONDS, build { flushIntervalSeconds = 0 }.flushIntervalSeconds)
        assertEquals(UnilitixConfig.MIN_FLUSH_INTERVAL_SECONDS, build { flushIntervalSeconds = -5 }.flushIntervalSeconds)
        assertTrue(build { flushIntervalSeconds = 0 }.flushIntervalSeconds > 0)
    }

    @Test
    fun `counts and sizes are clamped to usable values`() {
        val c = build {
            flushBatchSize = 0
            maxOfflineEvents = -1
            sessionTimeoutSeconds = -10
            maxSnapshotsPerSession = -1
            maxScreenshotsPerSession = -1
            screenshotIntervalMs = -1
            screenshotMaxWidth = 0
        }
        assertEquals(1, c.flushBatchSize)
        assertEquals(1, c.maxOfflineEvents)
        assertEquals(0, c.sessionTimeoutSeconds)
        assertEquals(0, c.maxSnapshotsPerSession)
        assertEquals(0, c.maxScreenshotsPerSession)
        assertEquals(0L, c.screenshotIntervalMs)
        assertEquals(UnilitixConfig.MIN_SCREENSHOT_WIDTH, c.screenshotMaxWidth)
    }

    @Test
    fun `screenshot quality is kept inside the range Bitmap compress accepts`() {
        assertEquals(0, build { screenshotQuality = -20 }.screenshotQuality)
        assertEquals(100, build { screenshotQuality = 250 }.screenshotQuality)
        assertEquals(55, build { screenshotQuality = 55 }.screenshotQuality)
    }

    @Test
    fun `sample rate is clamped to 0-1 and NaN means sample everything`() {
        assertEquals(0.0, build { sampleRate = -0.5 }.sampleRate, 0.0)
        assertEquals(1.0, build { sampleRate = 3.0 }.sampleRate, 0.0)
        assertEquals(1.0, build { sampleRate = Double.NaN }.sampleRate, 0.0)
        assertEquals(0.25, build { sampleRate = 0.25 }.sampleRate, 0.0)
    }
}
