package io.unilitix.sdk.network

import io.unilitix.sdk.util.Logger
import kotlinx.coroutines.delay
import kotlin.math.pow

internal sealed class ApiResult {
    data class Success(val sessionId: String = "") : ApiResult()
    data class ClientError(val code: Int, val message: String) : ApiResult()
    data class RateLimit(val retryAfterMs: Long) : ApiResult()
    data class ServerError(val code: Int) : ApiResult()
    data class NetworkError(val throwable: Throwable) : ApiResult()
}

internal object RetryPolicy {
    private const val MAX_RETRIES    = 5
    private const val BASE_DELAY_MS  = 1_000L
    private const val MAX_DELAY_MS   = 300_000L // 5-minute cap
    private const val BACKOFF_FACTOR = 2.0

    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0
        val delay = (BASE_DELAY_MS * BACKOFF_FACTOR.pow(attempt - 1)).toLong()
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    // Client errors in 400/401/403/422 are permanent — retrying won't help.
    fun shouldRetry(attempt: Int, result: ApiResult): Boolean {
        if (attempt >= MAX_RETRIES) return false
        return when (result) {
            is ApiResult.ClientError -> result.code !in listOf(400, 401, 403, 422)
            is ApiResult.Success     -> false
            else                     -> true
        }
    }

    suspend fun withRetry(action: suspend () -> ApiResult): ApiResult {
        var lastResult: ApiResult = ApiResult.NetworkError(RuntimeException("Not started"))

        for (attempt in 0..MAX_RETRIES) {
            when (val result = action()) {
                is ApiResult.Success -> return result

                is ApiResult.ClientError -> {
                    Logger.w("RetryPolicy: client error ${result.code} — dropping batch")
                    return result
                }

                is ApiResult.RateLimit -> {
                    Logger.w("RetryPolicy: rate limited, retrying after ${result.retryAfterMs}ms")
                    delay(result.retryAfterMs.coerceAtMost(MAX_DELAY_MS))
                    lastResult = result
                }

                is ApiResult.ServerError -> {
                    lastResult = result
                    if (shouldRetry(attempt, result)) {
                        val delayMs = delayForAttempt(attempt + 1)
                        Logger.w("RetryPolicy: server error ${result.code}, attempt $attempt, retrying in ${delayMs}ms")
                        delay(delayMs)
                    }
                }

                is ApiResult.NetworkError -> {
                    lastResult = result
                    if (shouldRetry(attempt, result)) {
                        val delayMs = delayForAttempt(attempt + 1)
                        Logger.w("RetryPolicy: network error, attempt $attempt, retrying in ${delayMs}ms")
                        Logger.e("Network error detail", result.throwable)
                        delay(delayMs)
                    }
                }
            }
        }

        Logger.e("RetryPolicy: all attempts exhausted, dropping batch")
        return lastResult
    }
}
