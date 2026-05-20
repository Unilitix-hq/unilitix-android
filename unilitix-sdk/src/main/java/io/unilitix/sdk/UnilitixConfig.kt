package io.unilitix.sdk

class UnilitixConfig {
    var apiUrl: String = "https://api.unilitix.io"
    var autoTrackScreens: Boolean = true
    var autoTrackTaps: Boolean = true
    var autoTrackCrashes: Boolean = true
    var autoTrackRageTaps: Boolean = true
    var flushIntervalSeconds: Int = 30
    var flushBatchSize: Int = 100
    var maxOfflineEvents: Int = 1000
    var sessionTimeoutSeconds: Int = 30
    var debugLogging: Boolean = false
    var maskInputs: Boolean = true
    var maskedViewIds: Set<Int> = emptySet()
    var sampleRate: Double = 1.0

    var captureSnapshots: Boolean = true
    var snapshotIntervalMs: Long = 500
    var maxSnapshotsPerSession: Int = 200

    var captureScreenshots: Boolean = true
    var screenshotIntervalMs: Long = 1000
    var screenshotQuality: Int = 30
    var screenshotMaxWidth: Int = 480
    var uploadScreenshotsOnWifiOnly: Boolean = true
    var maxScreenshotsPerSession: Int = 300
    var maskInputsInScreenshots: Boolean = true
}
