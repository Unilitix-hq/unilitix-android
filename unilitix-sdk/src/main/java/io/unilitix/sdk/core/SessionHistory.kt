package io.unilitix.sdk.core

/**
 * The last few sessions on this device, kept so a native crash read on the
 * next launch can be reported against the session that was running when
 * the process died (UnilitixCore keeps whole sessions in its event store;
 * this SDK's queue only holds unsent batches, so the session record is kept
 * here).
 *
 * Stored as one string in the SDK's preferences: one line per session,
 * oldest first, `id \t startedAt \t lastSeenAt \t sessionJson`. The JSON is
 * the record last persisted with a batch of that session (empty until the
 * first batch); JSON never contains a raw tab or newline.
 *
 * Cleared when consent is denied.
 */
internal class SessionHistory(
    private val kv: KeyValueStore,
    private val maxEntries: Int = MAX_ENTRIES
) {

    data class Entry(
        val id: String,
        val startedAt: Long,
        /** Last time the session was known alive (start, or its last persisted batch). */
        val lastSeenAt: Long,
        /** The session record as last sent, or null before its first batch. */
        val sessionJson: String?
    )

    @Synchronized
    fun entries(): List<Entry> = decode(kv.getString(KEY))

    /** A session started: kept even if it dies before its first batch. */
    @Synchronized
    fun recordStart(id: String, startedAt: Long) {
        upsert(Entry(id, startedAt, startedAt, null))
    }

    /** A batch of the session was persisted with [sessionJson] at [at]. */
    @Synchronized
    fun recordBatch(id: String, startedAt: Long, sessionJson: String, at: Long) {
        upsert(Entry(id, startedAt, at, sessionJson))
    }

    @Synchronized
    fun clear() {
        kv.putString(KEY, "")
    }

    /**
     * The session running at [crashedAt]: the one with the latest start at
     * or before `crashedAt + slackMs` (any start when [crashedAt] is null),
     * never [excludeId] (the session this process just started).
     */
    @Synchronized
    fun sessionAt(crashedAt: Long?, slackMs: Long = ATTRIBUTION_SLACK_MS, excludeId: String? = null): Entry? {
        val latestStart = crashedAt?.plus(slackMs)
        return entries()
            .filter { it.id != excludeId && (latestStart == null || it.startedAt <= latestStart) }
            .maxByOrNull { it.startedAt }
    }

    private fun upsert(entry: Entry) {
        val all = entries()
        val previous = all.firstOrNull { it.id == entry.id }
        val merged = if (previous != null) {
            entry.copy(
                lastSeenAt = maxOf(previous.lastSeenAt, entry.lastSeenAt),
                sessionJson = entry.sessionJson ?: previous.sessionJson
            )
        } else entry
        val kept = (all.filterNot { it.id == entry.id } + merged)
            .sortedBy { it.startedAt }
            .takeLast(maxEntries)
        kv.putString(KEY, encode(kept))
    }

    companion object {
        const val KEY = "session_history"
        const val MAX_ENTRIES = 8
        /**
         * A crash may carry a whole-second timestamp (the iOS handler writes
         * seconds): a session that started within the second it died still
         * matches. UnilitixCore uses the same 1 s.
         */
        const val ATTRIBUTION_SLACK_MS = 1_000L

        /** When the crashed session ended: its crash, or its last sign of life if later. */
        fun endedAt(entry: Entry, crashedAt: Long?): Long =
            if (crashedAt != null && crashedAt > entry.lastSeenAt) crashedAt else entry.lastSeenAt

        internal fun encode(entries: List<Entry>): String = entries.joinToString("\n") { e ->
            listOf(e.id, e.startedAt.toString(), e.lastSeenAt.toString(), e.sessionJson ?: "")
                .joinToString("\t")
        }

        internal fun decode(text: String?): List<Entry> {
            if (text.isNullOrEmpty()) return emptyList()
            return text.split('\n').mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 3 || parts[0].isEmpty()) return@mapNotNull null
                val started = parts[1].toLongOrNull() ?: return@mapNotNull null
                val seen = parts[2].toLongOrNull() ?: started
                val json = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                Entry(parts[0], started, seen, json)
            }
        }
    }
}
