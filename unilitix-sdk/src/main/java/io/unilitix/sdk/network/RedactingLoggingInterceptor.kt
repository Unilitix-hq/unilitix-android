package io.unilitix.sdk.network

import io.unilitix.sdk.BuildConfig
import io.unilitix.sdk.util.Logger
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
            Logger.d("→ ${request.method} ${request.url}")
            request.headers.toMultimap().forEach { (key, values) ->
                val display = if (key.lowercase() in sensitiveHeaders) "[REDACTED]" else values.joinToString()
                Logger.d("  $key: $display")
            }
        }
        return chain.proceed(chain.request())
    }
}
