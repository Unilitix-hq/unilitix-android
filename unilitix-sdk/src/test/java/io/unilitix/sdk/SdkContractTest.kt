package io.unilitix.sdk

import io.unilitix.sdk.capture.RageTapDetector
import io.unilitix.sdk.contract.SdkContract
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance with the shared SDK contract (api/sdk-contract.md in the api
 * repo). contract/SdkContract.kt is generated there by
 * `go run ./cmd/sdkcontract`; copy it over, never edit it by hand.
 *
 * Keys in [SdkContract.divergences] are known gaps with a planned fix and are
 * skipped; removing one from the contract turns its check back on.
 */
class SdkContractTest {

    private fun skipped(key: String) = key in SdkContract.divergences

    /** Int, Long and Double defaults compare by value. */
    private fun norm(v: Any?): Any? = if (v is Number) v.toDouble() else v

    @Test
    fun `config defaults match the contract`() {
        val c = UnilitixConfig.Builder().build()
        val actual: Map<String, Any> = mapOf(
            "apiUrl" to c.apiUrl,
            // Defaults to the library's BuildConfig.DEBUG: false in the
            // published release AAR, true in this debug unit-test build.
            "debugLogging" to (c.debugLogging && !BuildConfig.DEBUG),
            "autoTrackScreens" to c.autoTrackScreens,
            "autoTrackTaps" to c.autoTrackTaps,
            "autoTrackCrashes" to c.autoTrackCrashes,
            "autoTrackRageTaps" to c.autoTrackRageTaps,
            "flushIntervalSeconds" to c.flushIntervalSeconds,
            "flushBatchSize" to c.flushBatchSize,
            "maxOfflineEvents" to c.maxOfflineEvents,
            "sessionTimeoutSeconds" to c.sessionTimeoutSeconds,
            "maskInputs" to c.maskInputs,
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
        )
        for ((key, want) in SdkContract.defaults) {
            if (skipped(key)) continue
            assertTrue("contract option $key has no UnilitixConfig property", key in actual)
            assertEquals("default for $key", norm(want), norm(actual[key]))
        }
        val unlisted = actual.keys - SdkContract.defaults.keys
        assertTrue("options the contract does not list: $unlisted", unlisted.isEmpty())
    }

    @Test
    fun `fixed limits match the contract`() {
        assertEquals(SdkContract.MAX_BREADCRUMBS, Breadcrumbs.MAX_SIZE.toLong())
        assertEquals(SdkContract.RAGE_TAP_MIN_TAPS, RageTapDetector.MIN_TAPS.toLong())
        assertEquals(SdkContract.RAGE_TAP_WINDOW_MS, RageTapDetector.WINDOW_MS)
        assertEquals(SdkContract.RAGE_TAP_RADIUS, RageTapDetector.RADIUS_DP.toLong())
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
