package io.unilitix.sdk.util

import android.util.Log

/** Where log lines go. Swapped in JVM unit tests, where android.util.Log is a stub. */
internal interface LogSink {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable?)
}

internal object AndroidLogSink : LogSink {
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warn(tag: String, message: String) { Log.w(tag, message) }
    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

/**
 * SDK logger. Debug and info lines are emitted only when the host app sets
 * `UnilitixConfig.Builder.debugLogging = true` (default false); this works in
 * the published release AAR too. Warnings and errors are always emitted.
 *
 * Never log payloads, user IDs, traits or API keys: debug logging may be
 * enabled in production apps.
 */
internal object Logger {
    private const val TAG = "Unilitix"

    @Volatile internal var debugEnabled = false

    @Volatile internal var sink: LogSink = AndroidLogSink

    /** Applies `UnilitixConfig.debugLogging`; called from `Unilitix.init`. */
    fun configure(debugLogging: Boolean) {
        debugEnabled = debugLogging
    }

    fun d(message: String) {
        if (debugEnabled) sink.debug(TAG, message)
    }

    fun i(message: String) {
        if (debugEnabled) sink.info(TAG, message)
    }

    fun w(message: String) {
        sink.warn(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        sink.error(TAG, message, throwable)
    }

    /**
     * A non-secret label for an API key, for logs: its type prefix
     * (`"ul_live_"`, `"ul_test_"`), never any of the secret part.
     */
    fun keyLabel(apiKey: String): String {
        val cut = apiKey.lastIndexOf('_')
        return if (cut in 1..11) apiKey.substring(0, cut + 1) + "…" else "…"
    }
}
