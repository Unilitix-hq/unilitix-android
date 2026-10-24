package io.unilitix.sdk.capture

import io.unilitix.sdk.UnilitixConsent
import io.unilitix.sdk.core.SessionHistory

/**
 * Decides where each native crash (and, from 1.9.0, each ANR) of an earlier
 * process goes, as UnilitixCore (iOS) 0.5.0 does:
 * - consent denied: nowhere (dropped; the record and the exit watermark are
 *   consumed anyway, so it is never reported later);
 * - otherwise the session that was running when the process died: the stored
 *   session with the latest start at or before the crash time + 1 s
 *   ([SessionHistory.sessionAt]); that session is marked crashed and its
 *   duration ends at the crash;
 * - no stored session matches: the current (new) session, which is then
 *   marked crashed.
 * Pending consent keeps the report on the device like any other event.
 */
internal class NativeCrashReporter(private val history: SessionHistory) {

    /** [session] null: report in the current session. */
    data class Placement(val crash: NativeCrash, val session: SessionHistory.Entry?)

    fun place(crashes: List<NativeCrash>, consent: UnilitixConsent, currentSessionId: String?): List<Placement> {
        if (consent == UnilitixConsent.DENIED) return emptyList()
        return crashes
            .sortedBy { it.timestampMs ?: Long.MAX_VALUE }
            .map { Placement(it, history.sessionAt(it.timestampMs, SessionHistory.ATTRIBUTION_SLACK_MS, currentSessionId)) }
    }

    /** [session] null: report in the current session. */
    data class AnrPlacement(val anr: Anr, val session: SessionHistory.Entry?)

    /**
     * ANRs (1.9.0) go where native crashes go, by the same rules, except that
     * an ANR is not a crash: no session is marked crashed. The session that
     * was running still ends when the process was killed.
     */
    fun placeAnrs(anrs: List<Anr>, consent: UnilitixConsent, currentSessionId: String?): List<AnrPlacement> {
        if (consent == UnilitixConsent.DENIED) return emptyList()
        return anrs
            .sortedBy { it.timestampMs ?: Long.MAX_VALUE }
            .map { AnrPlacement(it, history.sessionAt(it.timestampMs, SessionHistory.ATTRIBUTION_SLACK_MS, currentSessionId)) }
    }
}
