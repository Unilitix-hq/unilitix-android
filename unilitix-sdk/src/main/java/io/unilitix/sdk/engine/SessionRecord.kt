package io.unilitix.sdk.engine

/**
 * What the engine keeps about one session, so its events are uploaded under
 * the identity they were captured with even after `reset()` or a restart
 * ("events captured before reset keep their session and ids"), and so a
 * native crash read on the next launch lands in the session that crashed.
 * Persisted as JSON in [EngineStore.putSession].
 */
internal class SessionRecord(
    val id: String,
    val anonymousId: String,
    var userId: String?,
    val installId: String,
    /** Wall clock ms. */
    val startedAt: Long,
    /** Last time the session was known alive (wall ms). */
    var lastSeenAt: Long,
    /** Wall ms when it ended (timeout, reset, crash), or null while it runs. */
    var endedAt: Long? = null,
    var crashed: Boolean = false,
    var foregroundMs: Long = 0,
    var backgroundMs: Long = 0,
    var offlineEvents: Int = 0,
    var onlineEvents: Int = 0,
    var networkTransitions: Int = 0,
    var nextSeq: Int = 0,
    var syncAttempts: Int = 0,
    var syncFailedBatches: Int = 0,
    var wrapper: String? = null,
    var wrapperVersion: String? = null,
    /** At least one batch acknowledged (2xx): the server knows the session, replay media may follow. */
    var acknowledged: Boolean = false,
    /** Session fields of a batch queued by SDK 1.10 or older (converted on upgrade). */
    var legacy: Map<String, Any?>? = null,
) {
    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = id
        m["anonymousId"] = anonymousId
        m["userId"] = userId
        m["installId"] = installId
        m["startedAt"] = startedAt
        m["lastSeenAt"] = lastSeenAt
        m["endedAt"] = endedAt
        m["crashed"] = crashed
        m["foregroundMs"] = foregroundMs
        m["backgroundMs"] = backgroundMs
        m["offlineEvents"] = offlineEvents
        m["onlineEvents"] = onlineEvents
        m["networkTransitions"] = networkTransitions
        m["nextSeq"] = nextSeq
        m["syncAttempts"] = syncAttempts
        m["syncFailedBatches"] = syncFailedBatches
        m["wrapper"] = wrapper
        m["wrapperVersion"] = wrapperVersion
        m["acknowledged"] = acknowledged
        if (legacy != null) m["legacy"] = legacy
        return EngineJson.write(m)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): SessionRecord? {
            val m = EngineJson.parseObject(json) ?: return null
            fun long(k: String): Long? = (m[k] as? Number)?.toLong()
            fun int(k: String): Int = (m[k] as? Number)?.toInt() ?: 0
            val id = m["id"] as? String ?: return null
            val started = long("startedAt") ?: return null
            return SessionRecord(
                id = id,
                anonymousId = m["anonymousId"] as? String ?: "",
                userId = m["userId"] as? String,
                installId = m["installId"] as? String ?: "",
                startedAt = started,
                lastSeenAt = long("lastSeenAt") ?: started,
                endedAt = long("endedAt"),
                crashed = m["crashed"] == true,
                foregroundMs = long("foregroundMs") ?: 0,
                backgroundMs = long("backgroundMs") ?: 0,
                offlineEvents = int("offlineEvents"),
                onlineEvents = int("onlineEvents"),
                networkTransitions = int("networkTransitions"),
                nextSeq = int("nextSeq"),
                syncAttempts = int("syncAttempts"),
                syncFailedBatches = int("syncFailedBatches"),
                wrapper = m["wrapper"] as? String,
                wrapperVersion = m["wrapperVersion"] as? String,
                acknowledged = m["acknowledged"] == true,
                legacy = m["legacy"] as? Map<String, Any?>,
            )
        }
    }
}
