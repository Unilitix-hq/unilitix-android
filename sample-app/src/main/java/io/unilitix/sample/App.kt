package io.unilitix.sample

import android.app.Application
import io.unilitix.sdk.Unilitix

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Unilitix.init(this, "ul_live_0ccc63c2-80cf-469f-964b-33eeaff6e800") {
            apiUrl = "http://10.0.2.2:4000"
            debugLogging = true
            flushIntervalSeconds = 10
        }
    }
}
