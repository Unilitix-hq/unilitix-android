package io.unilitix.sdk.network

import android.util.Log
import io.unilitix.sdk.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

internal class RedactingLoggingInterceptor : Interceptor {
    private val sensitiveHeaders = setOf(
        "x-unilitix-key",
        "authorization",
        "cookie",
        "set-cookie",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        if (BuildConfig.DEBUG) {
            val request = chain.request()
            Log.d(TAG, "→ ${request.method} ${request.url}")
            request.headers.toMultimap().forEach { (key, values) ->
                val display = if (key.lowercase() in sensitiveHeaders) "[REDACTED]" else values.joinToString()
                Log.d(TAG, "  $key: $display")
            }
        }
        return chain.proceed(chain.request())
    }

    companion object {
        private const val TAG = "Unilitix"
    }
}
