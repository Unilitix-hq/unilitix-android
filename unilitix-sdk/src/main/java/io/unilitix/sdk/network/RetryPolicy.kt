package io.unilitix.sdk.network

import io.unilitix.sdk.util.Logger
import kotlinx.coroutines.delay

internal sealed class ApiResult {
    data class Success(val sessionId: String = "") : ApiResult()
    data class ClientError(val code: Int, val message: String) : ApiResult()
    data class RateLimit(val retryAfterMs: Long) : ApiResult()
    data class ServerError(val code: Int) : ApiResult()
    data class NetworkError(val throwable: Throwable) : ApiResult()
}

internal object RetryPolicy {
    private val backoffDelays = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)

    suspend fun withRetry(action: suspend () -> ApiResult): ApiResult {
        var lastResult: ApiResult = ApiResult.NetworkError(RuntimeException("Not started"))

        for (attempt in 0..backoffDelays.size) {
            when (val result = action()) {
                is ApiResult.Success -> return result

                is ApiResult.ClientError -> {
                    Logger.w("RetryPolicy: client error ${result.code} — dropping batch")
                    return result
                }

                is ApiResult.RateLimit -> {
                    Logger.w("RetryPolicy: rate limited, retrying after ${result.retryAfterMs}ms")
                    delay(result.retryAfterMs)
                    lastResult = result
                }

                is ApiResult.ServerError -> {
                    lastResult = result
                    if (attempt < backoffDelays.size) {
                        val delayMs = backoffDelays[attempt]
                        Logger.w("RetryPolicy: server error ${result.code}, attempt $attempt, retrying in ${delayMs}ms")
                        delay(delayMs)
                    }
                }

                is ApiResult.NetworkError -> {
                    lastResult = result
                    if (attempt < backoffDelays.size) {
                        val delayMs = backoffDelays[attempt]
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
