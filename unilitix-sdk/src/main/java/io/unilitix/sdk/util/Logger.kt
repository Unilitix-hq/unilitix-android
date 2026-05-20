package io.unilitix.sdk.util

import android.util.Log

internal object Logger {
    private const val TAG = "Unilitix"
    @Volatile internal var debugEnabled = false

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }
}
