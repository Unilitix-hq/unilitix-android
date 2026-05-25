package io.unilitix.sdk.util

import android.util.Log
import io.unilitix.sdk.BuildConfig

internal object Logger {
    private const val TAG = "Unilitix"

    // Defaults to false in release builds — BuildConfig.DEBUG is a compile-time constant so
    // the compiler eliminates the entire debug log body in release, preventing any runtime
    // enablement of verbose logging via config flags.
    @Volatile internal var debugEnabled = BuildConfig.DEBUG

    fun d(message: String) {
        if (BuildConfig.DEBUG && debugEnabled) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG && debugEnabled) Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
