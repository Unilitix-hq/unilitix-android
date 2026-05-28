package io.unilitix.sdk

import io.unilitix.sdk.BuildConfig

/**
 * Immutable SDK configuration. Construct via [Builder] (Kotlin DSL or Java fluent API).
 *
 * Kotlin:
 * ```kotlin
 * Unilitix.init(app, apiKey) {
 *     apiUrl = "https://custom.host"
 *     debugLogging = true
 * }
 * ```
 *
 * Java:
 * ```java
 * Unilitix.init(app, apiKey, builder -> {
 *     builder.apiUrl("https://custom.host");
 *     builder.debugLogging(true);
 *     return Unit.INSTANCE;
 * });
 * ```
 */
data class UnilitixConfig internal constructor(
    val apiUrl: String,
    val autoTrackScreens: Boolean,
    val autoTrackTaps: Boolean,
    val autoTrackCrashes: Boolean,
    val autoTrackRageTaps: Boolean,
    val flushIntervalSeconds: Int,
    val flushBatchSize: Int,
    val maxOfflineEvents: Int,
    val sessionTimeoutSeconds: Int,
    val debugLogging: Boolean,
    val maskInputs: Boolean,
    val maskedViewIds: Set<Int>,
    val sampleRate: Double,
    val captureSnapshots: Boolean,
    val snapshotIntervalMs: Long,
    val maxSnapshotsPerSession: Int,
    val captureScreenshots: Boolean,
    val screenshotIntervalMs: Long,
    val screenshotQuality: Int,
    val screenshotMaxWidth: Int,
    val uploadScreenshotsOnWifiOnly: Boolean,
    val maxScreenshotsPerSession: Int,
    val maskInputsInScreenshots: Boolean,
) {
    /** DSL + Java builder. Var properties support Kotlin `property = value` syntax. */
    class Builder {
        var apiUrl: String = "https://api.unilitix.io"
        var autoTrackScreens: Boolean = true
        var autoTrackTaps: Boolean = true
        var autoTrackCrashes: Boolean = true
        var autoTrackRageTaps: Boolean = true
        var flushIntervalSeconds: Int = 30
        var flushBatchSize: Int = 100
        var maxOfflineEvents: Int = 1000
        var sessionTimeoutSeconds: Int = 1800
        var debugLogging: Boolean = BuildConfig.DEBUG
        var maskInputs: Boolean = true
        var maskedViewIds: Set<Int> = emptySet()
        var sampleRate: Double = 1.0
        var captureSnapshots: Boolean = true
        // snapshotIntervalMs is clamped to [1s, 60s] at build() time.
        var snapshotIntervalMs: Long = 1_000L
        var maxSnapshotsPerSession: Int = 200
        var captureScreenshots: Boolean = true
        var screenshotIntervalMs: Long = 1_000L
        var screenshotQuality: Int = 30
        var screenshotMaxWidth: Int = 480
        var uploadScreenshotsOnWifiOnly: Boolean = true
        var maxScreenshotsPerSession: Int = 300
        var maskInputsInScreenshots: Boolean = true

        // Method-style setters for Java interop (fluent API).
        fun apiUrl(v: String)                      = apply { apiUrl = v }
        fun autoTrackScreens(v: Boolean)           = apply { autoTrackScreens = v }
        fun autoTrackTaps(v: Boolean)              = apply { autoTrackTaps = v }
        fun autoTrackCrashes(v: Boolean)           = apply { autoTrackCrashes = v }
        fun autoTrackRageTaps(v: Boolean)          = apply { autoTrackRageTaps = v }
        fun flushIntervalSeconds(v: Int)           = apply { flushIntervalSeconds = v }
        fun flushBatchSize(v: Int)                 = apply { flushBatchSize = v }
        fun maxOfflineEvents(v: Int)               = apply { maxOfflineEvents = v }
        fun sessionTimeoutSeconds(v: Int)          = apply { sessionTimeoutSeconds = v }
        fun debugLogging(v: Boolean)               = apply { debugLogging = v }
        fun maskInputs(v: Boolean)                 = apply { maskInputs = v }
        fun maskedViewIds(v: Set<Int>)             = apply { maskedViewIds = v }
        fun sampleRate(v: Double)                  = apply { sampleRate = v }
        fun captureSnapshots(v: Boolean)           = apply { captureSnapshots = v }
        fun snapshotIntervalMs(v: Long)            = apply { snapshotIntervalMs = v }
        fun maxSnapshotsPerSession(v: Int)         = apply { maxSnapshotsPerSession = v }
        fun captureScreenshots(v: Boolean)         = apply { captureScreenshots = v }
        fun screenshotIntervalMs(v: Long)          = apply { screenshotIntervalMs = v }
        fun screenshotQuality(v: Int)              = apply { screenshotQuality = v }
        fun screenshotMaxWidth(v: Int)             = apply { screenshotMaxWidth = v }
        fun uploadScreenshotsOnWifiOnly(v: Boolean)= apply { uploadScreenshotsOnWifiOnly = v }
        fun maxScreenshotsPerSession(v: Int)       = apply { maxScreenshotsPerSession = v }
        fun maskInputsInScreenshots(v: Boolean)    = apply { maskInputsInScreenshots = v }

        fun build() = UnilitixConfig(
            apiUrl                    = apiUrl,
            autoTrackScreens          = autoTrackScreens,
            autoTrackTaps             = autoTrackTaps,
            autoTrackCrashes          = autoTrackCrashes,
            autoTrackRageTaps         = autoTrackRageTaps,
            flushIntervalSeconds      = flushIntervalSeconds,
            flushBatchSize            = flushBatchSize,
            maxOfflineEvents          = maxOfflineEvents,
            sessionTimeoutSeconds     = sessionTimeoutSeconds,
            debugLogging              = debugLogging,
            maskInputs                = maskInputs,
            maskedViewIds             = maskedViewIds,
            sampleRate                = sampleRate,
            captureSnapshots          = captureSnapshots,
            snapshotIntervalMs        = snapshotIntervalMs.coerceIn(1_000L, 60_000L),
            maxSnapshotsPerSession    = maxSnapshotsPerSession,
            captureScreenshots        = captureScreenshots,
            screenshotIntervalMs      = screenshotIntervalMs,
            screenshotQuality         = screenshotQuality,
            screenshotMaxWidth        = screenshotMaxWidth,
            uploadScreenshotsOnWifiOnly = uploadScreenshotsOnWifiOnly,
            maxScreenshotsPerSession  = maxScreenshotsPerSession,
            maskInputsInScreenshots   = maskInputsInScreenshots,
        )
    }
}
