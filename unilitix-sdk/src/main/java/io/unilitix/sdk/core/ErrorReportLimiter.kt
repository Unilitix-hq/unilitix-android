package io.unilitix.sdk.core

/**
 * Per-session caps on CRASH and EXCEPTION reports, from the shared SDK
 * contract: the same fingerprint is reported at most [maxPerFingerprint]
 * times per session (later ones are counted, not sent), and at most
 * [maxPerSession] reports are sent per session in total. Stops an error
 * thrown on every frame or every list row from flooding the queue.
 *
 * [resetSession] runs on every session start. Thread-safe: reports arrive
 * from the crash thread, the main thread and bridge threads.
 */
internal class ErrorReportLimiter(
    val maxPerFingerprint: Int = MAX_REPORTS_PER_FINGERPRINT,
    val maxPerSession: Int = MAX_ERROR_REPORTS_PER_SESSION,
) {
    private val seen = HashMap<String, Int>()
    private var reported = 0

    /**
     * Counts one occurrence of [fingerprint] and returns its number in this
     * session (1-based) if it should be sent, or null if it is suppressed.
     */
    @Synchronized
    fun admit(fingerprint: String): Int? {
        val n = (seen[fingerprint] ?: 0) + 1
        seen[fingerprint] = n
        if (n > maxPerFingerprint || reported >= maxPerSession) return null
        reported++
        return n
    }

    /** Occurrences of [fingerprint] counted this session, sent or not. */
    @Synchronized
    fun occurrences(fingerprint: String): Int = seen[fingerprint] ?: 0

    @Synchronized
    fun resetSession() {
        seen.clear()
        reported = 0
    }

    companion object {
        /** Contract `maxReportsPerFingerprint`. */
        const val MAX_REPORTS_PER_FINGERPRINT = 3
        /** Contract `maxErrorReportsPerSession`. */
        const val MAX_ERROR_REPORTS_PER_SESSION = 50
    }
}
