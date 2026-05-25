package io.unilitix.sample

import android.app.Application
import android.os.Build
import io.unilitix.sdk.Unilitix

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Unilitix.init(this, "ul_live_0ccc63c2-80cf-469f-964b-33eeaff6e800") {
            apiUrl = "http://10.0.2.2:4000"
            debugLogging = true
            flushIntervalSeconds = 10
            autoTrackScreens = true
            autoTrackTaps = true
            autoTrackRageTaps = true
        }

        // Identify a default test user on launch so userId is never blank in backend data
        val testUserId = "demo_user_${Build.MODEL.replace(" ", "_").lowercase()}"
        Unilitix.identify(testUserId, mapOf(
            "device" to Build.MODEL,
            "plan" to "free",
            "source" to "sample_app"
        ))
    }
}
