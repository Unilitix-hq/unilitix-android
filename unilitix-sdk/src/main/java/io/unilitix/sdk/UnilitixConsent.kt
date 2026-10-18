package io.unilitix.sdk

/**
 * The user's consent to analytics and replay collection (shared SDK contract:
 * `granted | pending | denied`, default `granted`).
 *
 * * [GRANTED] — capture and upload. The default for apps that do not ask.
 * * [PENDING] — capture into the on-device queue but upload nothing (events,
 *   snapshots, screenshots) until the app calls
 *   `Unilitix.setConsent(UnilitixConsent.GRANTED)` (everything held is then
 *   sent) or `DENIED` (everything held is deleted). Start in this state when
 *   the app shows a consent screen after launch (NDPR / GDPR).
 * * [DENIED] — capture nothing, upload nothing, and delete everything queued
 *   on the device. Same as `Unilitix.optOut()`.
 *
 * A decision made with `Unilitix.setConsent` (or `optOut` / `optIn`) is
 * persisted and wins over the value passed to `Unilitix.init` on later
 * launches.
 */
enum class UnilitixConsent {
    GRANTED,
    PENDING,
    DENIED;

    /** The contract's wire name: `"granted"`, `"pending"` or `"denied"`. */
    val value: String
        get() = when (this) {
            GRANTED -> "granted"
            PENDING -> "pending"
            DENIED -> "denied"
        }

    companion object {
        /**
         * Parses a contract wire name (`"granted"`, `"pending"`, `"denied"`,
         * case-insensitive), e.g. from a cross-platform bridge. Returns null
         * for anything else.
         */
        @JvmStatic
        fun fromValue(value: String?): UnilitixConsent? {
            if (value == null) return null
            val v = value.trim()
            for (c in values()) {
                if (c.value.equals(v, ignoreCase = true)) return c
            }
            return null
        }
    }
}
