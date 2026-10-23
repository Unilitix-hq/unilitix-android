package io.unilitix.sdk.network

import io.unilitix.sdk.util.Logger
import kotlin.random.Random

internal sealed class ApiResult {
    data class Success(val sessionId: String = "") : ApiResult()
    /** A 4xx the server will never accept (not 401/403/408/429): the batch is dropped. */
    data class ClientError(val code: Int, val message: String) : ApiResult()
    /** 401/403: the API key is wrong or revoked. Uploads pause; data is kept. */
    data class AuthError(val code: Int) : ApiResult()
    /** 408 or 429. [retryAfterSeconds] is the raw `Retry-After` value, if any. */
    data class RateLimit(val code: Int = 429, val retryAfterSeconds: Long? = null) : ApiResult()
    data class ServerError(val code: Int, val retryAfterSeconds: Long? = null) : ApiResult()
    data class NetworkError(val throwable: Throwable) : ApiResult()

    companion object {
        /** Maps a non-2xx response to its result. */
        fun failure(code: Int, retryAfterHeader: String?, message: String = ""): ApiResult {
            val retryAfter = RetryPolicy.parseRetryAfter(retryAfterHeader)
            return when {
                RetryPolicy.isAuthFailure(code) -> AuthError(code)
                code == 408 || code == 429      -> RateLimit(code, retryAfter)
                code in 400..499                -> ClientError(code, message)
                else                            -> ServerError(code, retryAfter)
            }
        }
    }
}

/** What the uploader does after one request (see the contract's "Upload and retry"). */
internal enum class UploadOutcome {
    /** 2xx: delete the batch; any backoff window is closed. */
    SENT,
    /** No response, 408, 429, 5xx: keep the data; a backoff window is open. */
    RETRY_LATER,
    /** 401/403: keep the data; uploads are paused for the life of the process. */
    AUTH_REJECTED,
    /** Any other 4xx: drop that batch, no backoff. */
    DROPPED,
}

/**
 * Retry rules from the shared SDK contract, ported from the Flutter SDK
 * (2.1.1): one attempt per request per flush cycle, never blocking retries
 * inside one flush.
 *
 * Backoff after the n-th consecutive failure (n from 0) is
 * `min(1000 ms × 2^n, 300000 ms)` plus 0–20% jitter. A `Retry-After` header
 * (seconds) replaces it, clamped to 1–60 s.
 */
internal object RetryPolicy {
    const val BASE_DELAY_MS            = 1_000L
    const val MAX_DELAY_MS             = 300_000L // 5-minute cap
    const val JITTER_FRACTION          = 0.2
    const val RETRY_AFTER_MIN_SECONDS  = 1L
    const val RETRY_AFTER_MAX_SECONDS  = 60L

    /** 401 and 403 mean the key itself is wrong, not that the request was. */
    fun isAuthFailure(code: Int): Boolean = code == 401 || code == 403

    fun classify(result: ApiResult): UploadOutcome = when (result) {
        is ApiResult.Success      -> UploadOutcome.SENT
        is ApiResult.AuthError    -> UploadOutcome.AUTH_REJECTED
        is ApiResult.ClientError  -> UploadOutcome.DROPPED
        is ApiResult.RateLimit,
        is ApiResult.ServerError,
        is ApiResult.NetworkError -> UploadOutcome.RETRY_LATER
    }

    /** `Retry-After` in delta-seconds; HTTP-date values are ignored (null). */
    fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()

    /** Backoff window after [failures] consecutive failures (0-based), with jitter. */
    fun backoffMs(failures: Int, random: Random = Random.Default): Long {
        val n = failures.coerceIn(0, 20) // 2^20 s is far past the cap; avoids overflow
        val base = (BASE_DELAY_MS shl n).coerceAtMost(MAX_DELAY_MS)
        val jitter = (base * JITTER_FRACTION * random.nextDouble()).toLong()
        return base + jitter
    }

    /** A `Retry-After` value in seconds, clamped to 1–60 s, in milliseconds. */
    fun retryAfterMs(seconds: Long): Long =
        seconds.coerceIn(RETRY_AFTER_MIN_SECONDS, RETRY_AFTER_MAX_SECONDS) * 1_000L

    /** The window to wait: `Retry-After` when the server sent one, else [backoffMs]. */
    fun delayFor(failures: Int, retryAfterSeconds: Long?, random: Random = Random.Default): Long =
        if (retryAfterSeconds != null) retryAfterMs(retryAfterSeconds) else backoffMs(failures, random)

    fun retryAfterOf(result: ApiResult): Long? = when (result) {
        is ApiResult.RateLimit   -> result.retryAfterSeconds
        is ApiResult.ServerError -> result.retryAfterSeconds
        else                     -> null
    }
}

/**
 * Upload state shared by every uploader in the process: the backoff window
 * and the 401/403 latch. Every request outcome goes through [note]; every
 * uploader checks [isPaused] before sending anything.
 *
 * The auth latch lasts for the life of this object, which is the life of the
 * process (`Unilitix.init` runs once per process): a fixed key takes effect on
 * the next launch, and nothing captured meanwhile is deleted.
 *
 * @param clock monotonic milliseconds.
 */
internal class UploadGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: Random = Random.Default,
) {
    @Volatile
    var authRejected: Boolean = false
        private set

    /** Consecutive retryable failures; reset by the first success. */
    var consecutiveFailures: Int = 0
        private set

    private var backoffUntil: Long? = null

    /** Milliseconds left in the backoff window; 0 when uploads may proceed. */
    @Synchronized
    fun remainingBackoffMs(): Long {
        val until = backoffUntil ?: return 0L
        val left = until - clock()
        if (left <= 0L) {
            backoffUntil = null
            return 0L
        }
        return left
    }

    val inBackoff: Boolean get() = remainingBackoffMs() > 0L

    /** True when no upload may happen now (key rejected, or inside a backoff window). */
    val isPaused: Boolean get() = authRejected || inBackoff

    /** Records the outcome of one request and updates the backoff/auth state. */
    @Synchronized
    fun note(result: ApiResult, what: String): UploadOutcome {
        val outcome = RetryPolicy.classify(result)
        when (outcome) {
            UploadOutcome.SENT -> {
                if (consecutiveFailures > 0) Logger.d("Unilitix: $what succeeded — backoff cleared")
                consecutiveFailures = 0
                backoffUntil = null
            }
            UploadOutcome.AUTH_REJECTED -> {
                if (!authRejected) {
                    authRejected = true
                    val code = (result as? ApiResult.AuthError)?.code
                    Logger.w(
                        "Unilitix: API key rejected (HTTP $code). Uploads are paused until the app " +
                            "restarts — check the key passed to Unilitix.init(). Captured data stays " +
                            "on the device and is sent once the key is valid."
                    )
                }
            }
            UploadOutcome.RETRY_LATER -> {
                val delay = RetryPolicy.delayFor(consecutiveFailures, RetryPolicy.retryAfterOf(result), random)
                consecutiveFailures++
                backoffUntil = clock() + delay
                Logger.d("Unilitix: $what failed (${describe(result)}) — backing off ${delay}ms (failure #$consecutiveFailures)")
            }
            UploadOutcome.DROPPED -> {
                Logger.w("Unilitix: $what rejected by the API (${describe(result)}) — dropped")
            }
        }
        return outcome
    }

    private fun describe(result: ApiResult): String = when (result) {
        is ApiResult.Success      -> "ok"
        is ApiResult.ClientError  -> "HTTP ${result.code}"
        is ApiResult.AuthError    -> "HTTP ${result.code}"
        is ApiResult.RateLimit    -> "HTTP ${result.code}" + (result.retryAfterSeconds?.let { ", Retry-After ${it}s" } ?: "")
        is ApiResult.ServerError  -> "HTTP ${result.code}" + (result.retryAfterSeconds?.let { ", Retry-After ${it}s" } ?: "")
        is ApiResult.NetworkError -> "network error: ${result.throwable.javaClass.simpleName}"
    }
}
