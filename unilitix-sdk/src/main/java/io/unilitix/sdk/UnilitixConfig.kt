package io.unilitix.sdk

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
    val maskAllText: Boolean,
    val maskImages: Boolean,
    val maxScreenshotsPerMinute: Int,
    val screenshotHeartbeatMs: Long,
) {
    /** DSL + Java builder. Var properties support Kotlin `property = value` syntax. */
    class Builder {
        var apiUrl: String = "https://api.unilitix.com"
        var autoTrackScreens: Boolean = true
        var autoTrackTaps: Boolean = true
        var autoTrackCrashes: Boolean = true
        var autoTrackRageTaps: Boolean = true
        var flushIntervalSeconds: Int = 30
        var flushBatchSize: Int = 100
        var maxOfflineEvents: Int = 1000
        var sessionTimeoutSeconds: Int = 1800
        /**
         * Verbose SDK logs (tag `Unilitix`). Default false; works in release
         * builds of your app. Payloads, user IDs, traits and the API key are
         * never logged. Warnings and errors are always logged.
         */
        var debugLogging: Boolean = false
        /**
         * Consent state used when the user has not decided yet on this device;
         * a decision persisted by [Unilitix.setConsent] on an earlier launch
         * wins. Default [UnilitixConsent.GRANTED]. The `consent` argument of
         * `Unilitix.init(app, key, consent) { }` overrides this.
         */
        var consent: UnilitixConsent = UnilitixConsent.GRANTED
        var maskInputs: Boolean = true
        var maskedViewIds: Set<Int> = emptySet()
        var sampleRate: Double = 1.0
        var captureSnapshots: Boolean = true
        // snapshotIntervalMs is clamped to [1s, 60s] at build() time.
        var snapshotIntervalMs: Long = 1_000L
        /** Stop capturing view-tree snapshots after this many in one session. Default 1500. */
        var maxSnapshotsPerSession: Int = 1500
        var captureScreenshots: Boolean = true
        /** Minimum gap between two screenshots. Default 1000 ms. */
        var screenshotIntervalMs: Long = 1_000L
        var screenshotQuality: Int = 30
        var screenshotMaxWidth: Int = 480
        var uploadScreenshotsOnWifiOnly: Boolean = true
        /** Stop capturing screenshots after this many in one session. Default 1500. */
        var maxScreenshotsPerSession: Int = 1500
        var maskInputsInScreenshots: Boolean = true
        /**
         * Mask all text in session replay, not only inputs. Default true.
         *
         * Screenshots: every visible `TextView` (including `Button`,
         * `EditText` and other subclasses) is painted over. Snapshots: text
         * keeps its word shapes and length but every character becomes `•`.
         * Exempt views that carry no personal data (headings, button labels,
         * navigation) with [Unilitix.unmask]. Inputs are always governed by
         * [maskInputs] / [maskInputsInScreenshots], whatever `unmask` says.
         *
         * Jetpack Compose text is not covered: the SDK does not walk Compose
         * semantics. Wrap sensitive Compose content in a view annotated
         * [UnilitixPrivate] or list its host view in [maskedViewIds].
         */
        var maskAllText: Boolean = true
        /**
         * Replace images with placeholders in session replay. Default false.
         * Screenshots: every `ImageView` with a drawable, and every leaf view
         * whose background is a bitmap, is painted over. Snapshots: image
         * nodes are marked `masked` and carry no content. [Unilitix.unmask]
         * exempts a view and its subtree.
         */
        var maskImages: Boolean = false
        /**
         * Rolling one-minute screenshot budget, so a screen that animates
         * continuously cannot flood the session. Default 20.
         */
        var maxScreenshotsPerMinute: Int = 20
        /**
         * Take a screenshot at least this often on a static screen, so the
         * replay shows the screen is still up. Default 10000 ms; 0 turns the
         * heartbeat off. Never shorter than [screenshotIntervalMs].
         */
        var screenshotHeartbeatMs: Long = 10_000L

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
        fun consent(v: UnilitixConsent)            = apply { consent = v }
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
        fun maskAllText(v: Boolean)                = apply { maskAllText = v }
        fun maskImages(v: Boolean)                 = apply { maskImages = v }
        fun maxScreenshotsPerMinute(v: Int)        = apply { maxScreenshotsPerMinute = v }
        fun screenshotHeartbeatMs(v: Long)         = apply { screenshotHeartbeatMs = v }

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
            maskAllText               = maskAllText,
            maskImages                = maskImages,
            maxScreenshotsPerMinute   = maxScreenshotsPerMinute.coerceAtLeast(0),
            screenshotHeartbeatMs     = screenshotHeartbeatMs.coerceAtLeast(0L),
        )
    }
}
