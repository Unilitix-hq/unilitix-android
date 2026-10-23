package io.unilitix.sdk

import io.unilitix.sdk.capture.RageTapDetector
import io.unilitix.sdk.contract.SdkContract
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.ErrorReportLimiter
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.network.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance with the shared SDK contract (api/sdk-contract.md in the api
 * repo). contract/SdkContract.kt is generated there by
 * `go run ./cmd/sdkcontract`; copy it over, never edit it by hand.
 *
 * Keys in [SdkContract.divergences] are known gaps with a planned fix and are
 * skipped, except [FIXED_IN_1_6_0]: this release closes those gaps, so they
 * are checked even while the contract still lists them.
 */
class SdkContractTest {

    /**
     * Divergences fixed in unilitix-android 1.6.0. Remove this set once the
     * contract no longer lists them; the generic checks then cover them.
     */
    private val FIXED_IN_1_6_0 = setOf(
        "maskAllText", "maskImages", "maxScreenshotsPerMinute", "screenshotHeartbeatMs",
        "maxScreenshotsPerSession", "maxSnapshotsPerSession", "retry", "maxReportsPerFingerprint",
    )

    private fun skipped(key: String) = key in SdkContract.divergences && key !in FIXED_IN_1_6_0

    /** Int, Long and Double defaults compare by value. */
    private fun norm(v: Any?): Any? = if (v is Number) v.toDouble() else v

    private fun actualDefaults(): Map<String, Any> {
        val c = UnilitixConfig.Builder().build()
        return mapOf(
            "apiUrl" to c.apiUrl,
            "debugLogging" to c.debugLogging,
            // Builder-only: the initial state when the user has not decided yet.
            "consent" to UnilitixConfig.Builder().consent.value,
            "autoTrackScreens" to c.autoTrackScreens,
            "autoTrackTaps" to c.autoTrackTaps,
            "autoTrackCrashes" to c.autoTrackCrashes,
            "autoTrackRageTaps" to c.autoTrackRageTaps,
            "flushIntervalSeconds" to c.flushIntervalSeconds,
            "flushBatchSize" to c.flushBatchSize,
            "maxOfflineEvents" to c.maxOfflineEvents,
            "sessionTimeoutSeconds" to c.sessionTimeoutSeconds,
            "maskInputs" to c.maskInputs,
            "maskAllText" to c.maskAllText,
            "maskImages" to c.maskImages,
            "maskInputsInScreenshots" to c.maskInputsInScreenshots,
            "sampleRate" to c.sampleRate,
            "captureSnapshots" to c.captureSnapshots,
            "snapshotIntervalMs" to c.snapshotIntervalMs,
            "maxSnapshotsPerSession" to c.maxSnapshotsPerSession,
            "captureScreenshots" to c.captureScreenshots,
            "screenshotIntervalMs" to c.screenshotIntervalMs,
            "screenshotQuality" to c.screenshotQuality,
            "screenshotMaxWidth" to c.screenshotMaxWidth,
            "uploadScreenshotsOnWifiOnly" to c.uploadScreenshotsOnWifiOnly,
            "maxScreenshotsPerSession" to c.maxScreenshotsPerSession,
            "maxScreenshotsPerMinute" to c.maxScreenshotsPerMinute,
            "screenshotHeartbeatMs" to c.screenshotHeartbeatMs,
        )
    }

    @Test
    fun `config defaults match the contract`() {
        val actual = actualDefaults()
        for ((key, want) in SdkContract.defaults) {
            if (skipped(key)) continue
            assertTrue("contract option $key has no UnilitixConfig property", key in actual)
            assertEquals("default for $key", norm(want), norm(actual[key]))
        }
        val unlisted = actual.keys - SdkContract.defaults.keys
        assertTrue("options the contract does not list: $unlisted", unlisted.isEmpty())
    }

    @Test
    fun `1_6_0 replay defaults match the contract`() {
        val actual = actualDefaults()
        for (key in listOf(
            "maskAllText", "maskImages", "maxScreenshotsPerMinute", "screenshotHeartbeatMs",
            "maxScreenshotsPerSession", "maxSnapshotsPerSession",
        )) {
            assertTrue("contract lists $key", key in SdkContract.defaults)
            assertEquals("default for $key", norm(SdkContract.defaults[key]), norm(actual[key]))
        }
    }

    @Test
    fun `fixed limits match the contract`() {
        assertEquals(SdkContract.MAX_BREADCRUMBS, Breadcrumbs.MAX_SIZE.toLong())
        assertEquals(SdkContract.RAGE_TAP_MIN_TAPS, RageTapDetector.MIN_TAPS.toLong())
        assertEquals(SdkContract.RAGE_TAP_WINDOW_MS, RageTapDetector.WINDOW_MS)
        assertEquals(SdkContract.RAGE_TAP_RADIUS, RageTapDetector.RADIUS_DP.toLong())
        assertEquals(SdkContract.MAX_REPORTS_PER_FINGERPRINT, ErrorReportLimiter.MAX_REPORTS_PER_FINGERPRINT.toLong())
        assertEquals(SdkContract.MAX_ERROR_REPORTS_PER_SESSION, ErrorReportLimiter.MAX_ERROR_REPORTS_PER_SESSION.toLong())
    }

    @Test
    fun `retry constants match the contract`() {
        assertEquals(SdkContract.RETRY_BASE_DELAY_MS, RetryPolicy.BASE_DELAY_MS)
        assertEquals(SdkContract.RETRY_MAX_DELAY_MS, RetryPolicy.MAX_DELAY_MS)
        assertEquals(SdkContract.RETRY_AFTER_MIN_SECONDS, RetryPolicy.RETRY_AFTER_MIN_SECONDS)
        assertEquals(SdkContract.RETRY_AFTER_MAX_SECONDS, RetryPolicy.RETRY_AFTER_MAX_SECONDS)
    }

    @Test
    fun `rage tap radius scales with density`() {
        var fired = 0
        val detector = RageTapDetector(density = 3f) { _, _, _ -> fired++ }
        // 250 physical px apart is 83 dp on a 3x screen: inside the 100 dp radius.
        detector.recordTap(0f, 0f, "Home")
        detector.recordTap(250f, 0f, "Home")
        detector.recordTap(125f, 0f, "Home")
        assertEquals(1, fired)
    }

    @Test
    fun `event types the SDK sends are canonical`() {
        for (t in listOf("SESSION_START", "TAP", "RAGE_TAP", "NAVIGATE", "CUSTOM", "EXCEPTION", "CRASH")) {
            assertTrue("$t is not in the contract", t in SdkContract.eventTypes)
        }
    }

    @Test
    fun `fingerprint matches every contract vector`() {
        assertTrue(SdkContract.fingerprintVectors.isNotEmpty())
        for (v in SdkContract.fingerprintVectors) {
            assertEquals(v.name, v.fingerprint, Fingerprint.of(v.exceptionType, v.stack))
        }
    }
}
