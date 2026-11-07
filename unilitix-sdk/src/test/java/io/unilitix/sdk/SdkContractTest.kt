package io.unilitix.sdk

import io.unilitix.sdk.capture.AnrTrace
import io.unilitix.sdk.capture.RageTapDetector
import io.unilitix.sdk.contract.SdkContract
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.ErrorReportLimiter
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.core.NativeFrames
import io.unilitix.sdk.engine.Normalize
import io.unilitix.sdk.engine.NetworkTypes
import io.unilitix.sdk.engine.UnilitixCore
import io.unilitix.sdk.network.ApiClient
import io.unilitix.sdk.network.RetryPolicy
import io.unilitix.sdk.network.UploadOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance with the shared SDK contract (api/sdk-contract.md in the api
 * repo). contract/SdkContract.kt is generated there by
 * `go run ./cmd/sdkcontract`; copy it over, never edit it by hand.
 *
 * Keys in [SdkContract.divergences] are known gaps with a planned fix and are
 * skipped. Since 1.6.0 Android has none. Behaviour (contract 1.4.0) is
 * checked by contract/BehaviourFixturesTest against the shared fixtures.
 */
class SdkContractTest {

    private fun skipped(key: String) = key in SdkContract.divergences

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
    fun `1_4_0 limits match the contract`() {
        assertEquals(SdkContract.PRE_INIT_BUFFER_SIZE, UnilitixCore.PRE_INIT_BUFFER_SIZE.toLong())
        assertEquals(SdkContract.MAX_PROPERTY_DEPTH, Normalize.MAX_DEPTH.toLong())
        assertEquals(SdkContract.MAX_PROPERTY_KEYS, Normalize.MAX_KEYS.toLong())
        assertEquals(SdkContract.MAX_STRING_LENGTH, Normalize.MAX_STRING_LENGTH.toLong())
        assertEquals(SdkContract.TRUNCATED_MARKER, Normalize.TRUNCATED)
        assertEquals(SdkContract.PAYMENT_BACKOFF_MS, RetryPolicy.PAYMENT_BACKOFF_MS)
        assertEquals(SdkContract.PAYMENT_RETRY_AFTER_MAX_SECONDS, RetryPolicy.PAYMENT_RETRY_AFTER_MAX_SECONDS)
        assertEquals(SdkContract.REQUEST_TIMEOUT_SECONDS, ApiClient.REQUEST_TIMEOUT_SECONDS)
        assertEquals(SdkContract.FINGERPRINT_FRAMES, Fingerprint.FRAMES.toLong())
    }

    @Test
    fun `network types and rules match the contract`() {
        assertEquals(SdkContract.networkTypes, NetworkTypes.ALL)
        for ((rule, want) in SdkContract.networkTypeRules) {
            val (transport, generation, online) = rule.split('/')
            val gens = if (generation == "*") listOf("2g", "3g", "4g", "5g", "unknown") else listOf(generation)
            val transports = if (transport == "*") listOf("wifi", "ethernet", "cellular", "none", "other") else listOf(transport)
            for (t in transports) for (g in gens) {
                assertEquals("$rule ($t/$g)", want, NetworkTypes.canonical(t, g, online == "online"))
            }
        }
    }

    @Test
    fun `retry classification matches the contract`() {
        val names = mapOf(
            UploadOutcome.SENT to "sent", UploadOutcome.RETRY_LATER to "retry", UploadOutcome.AUTH_REJECTED to "auth",
            UploadOutcome.PAYMENT_PAUSED to "payment", UploadOutcome.DROPPED to "drop",
        )
        for ((status, want) in SdkContract.retryStatusOutcomes) {
            assertEquals("status $status", want, names[RetryPolicy.classifyStatus(status.toInt())])
        }
        assertEquals(120_000L, RetryPolicy.paymentDelayMs(120))
        assertEquals(3_600_000L, RetryPolicy.paymentDelayMs(86_400))
        assertEquals(1_000L, RetryPolicy.paymentDelayMs(0))
        assertEquals(900_000L, RetryPolicy.paymentDelayMs(null))
    }

    @Test
    fun `native system images and path prefixes match the contract`() {
        assertEquals(SdkContract.nativeSystemImages, NativeFrames.SYSTEM_IMAGES)
        assertEquals(SdkContract.nativeSystemPathPrefixes, NativeFrames.SYSTEM_PATH_PREFIXES)
    }

    @Test
    fun `rage tap radius is 100 logical px`() {
        // Taps arrive in dp (logical px), so the contract radius applies as is.
        var fired = 0
        val detector = RageTapDetector { _, _, _ -> fired++ }
        // The cluster is measured around the newest tap.
        detector.recordTap(0f, 0f, "Home")
        detector.recordTap(100f, 0f, "Home")
        detector.recordTap(0f, 100f, "Home")
        assertEquals("100 dp apart per axis is inside the radius", 1, fired)

        val far = RageTapDetector { _, _, _ -> fired++ }
        far.recordTap(0f, 0f, "Home")
        far.recordTap(101f, 0f, "Home")
        far.recordTap(0f, 0f, "Home")
        assertEquals("101 dp apart is outside the radius", 1, fired)
    }

    @Test
    fun `event types the SDK sends are canonical`() {
        for (t in listOf("SESSION_START", "TAP", "RAGE_TAP", "NAVIGATE", "CUSTOM", "EXCEPTION", "CRASH", AnrTrace.TYPE)) {
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

    @Test
    fun `crash handler waits no longer than the fatal flush budget`() {
        assertEquals(
            SdkContract.FATAL_FLUSH_BUDGET_MS,
            io.unilitix.sdk.capture.CrashHandler.FATAL_FLUSH_BUDGET_MS
        )
    }

    @Test
    fun `secure windows are never captured`() {
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        assertTrue(io.unilitix.sdk.capture.ReplayMasking.isSecureWindow(secure or 0x100))
        assertTrue(!io.unilitix.sdk.capture.ReplayMasking.isSecureWindow(0x100))
    }
}
