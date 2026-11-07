package io.unilitix.sdk.engine

import io.unilitix.sdk.core.KeyValueStore

/**
 * An [EngineStore] in memory. The behaviour-fixture runner uses one instance
 * as the device's "disk" across simulated restarts; on Android it is the
 * fallback when the queue database cannot be opened (events then live only
 * as long as the process, instead of the SDK failing to start).
 */
internal class MemoryEngineStore : EngineStore {

    class MemoryKv : KeyValueStore {
        private val map = HashMap<String, Any>()
        @Synchronized override fun getString(key: String): String? = (map[key] as? String)?.takeIf { it.isNotEmpty() }
        @Synchronized override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        @Synchronized override fun putString(key: String, value: String) { map[key] = value }
        @Synchronized override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        @Synchronized override fun remove(key: String) { map.remove(key) }
    }

    private class Row(val id: Long, val sessionId: String, val json: String, var attempts: Int = 0, var parked: Boolean = false, var parkCycles: Int = 0)

    override val prefs = MemoryKv()
    override val device = MemoryKv()
    override val identity = MemoryKv()

    private val events = ArrayList<Row>()
    private var nextId = 1L
    private val sessions = LinkedHashMap<String, Pair<Long, String>>()

    @Synchronized override fun appendEvent(sessionId: String, json: String) {
        events.add(Row(nextId++, sessionId, json))
    }

    @Synchronized override fun eventCount(): Int = events.size

    @Synchronized override fun dropOldestEvents(count: Int) {
        repeat(count.coerceAtMost(events.size)) { events.removeAt(0) }
    }

    @Synchronized override fun oldestEvents(limit: Int): List<StoredEvent> =
        events.asSequence().filter { !it.parked }.take(limit).map { StoredEvent(it.id, it.sessionId, it.json, it.attempts) }.toList()

    @Synchronized override fun deleteEvents(ids: List<Long>) {
        val set = ids.toHashSet()
        events.removeAll { it.id in set }
    }

    @Synchronized override fun recordFailedAttempt(ids: List<Long>, parkAfter: Int) {
        val set = ids.toHashSet()
        for (r in events) if (r.id in set) {
            r.attempts++
            if (r.attempts >= parkAfter) r.parked = true
        }
    }

    @Synchronized override fun unparkEvents(maxCycles: Int): Int {
        var deleted = 0
        val it = events.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (!r.parked) continue
            r.parkCycles++
            if (r.parkCycles >= maxCycles) { it.remove(); deleted++ } else { r.parked = false; r.attempts = 0 }
        }
        return deleted
    }

    @Synchronized override fun deleteAllEvents() { events.clear() }

    @Synchronized override fun sessionIdsWithEvents(): Set<String> = events.mapTo(HashSet()) { it.sessionId }

    @Synchronized override fun putSession(id: String, startedAt: Long, json: String) { sessions[id] = startedAt to json }

    @Synchronized override fun session(id: String): String? = sessions[id]?.second

    @Synchronized override fun recentSessions(limit: Int): List<String> =
        sessions.values.sortedByDescending { it.first }.take(limit).map { it.second }

    @Synchronized override fun deleteSessionsExcept(keep: Set<String>) {
        sessions.keys.retainAll(keep)
    }
}
