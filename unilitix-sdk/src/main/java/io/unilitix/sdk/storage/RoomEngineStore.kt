package io.unilitix.sdk.storage

import io.unilitix.sdk.core.KeyValueStore
import io.unilitix.sdk.engine.EngineJson
import io.unilitix.sdk.engine.EngineStore
import io.unilitix.sdk.engine.PriorBatch
import io.unilitix.sdk.engine.StoredEvent
import io.unilitix.sdk.util.Logger

/**
 * The engine's [EngineStore] on the SDK's Room database and preferences.
 * Called on the engine thread (and, for session lookups, the replay upload
 * thread); Room serialises writes itself.
 */
internal class RoomEngineStore(
    private val db: EventDatabase,
    override val prefs: KeyValueStore,
    override val device: KeyValueStore,
    override val identity: KeyValueStore,
) : EngineStore {

    private val dao = db.queueDao()

    override fun appendEvent(sessionId: String, json: String) {
        dao.insert(QueuedEventEntity(sessionId = sessionId, json = json))
    }

    override fun eventCount(): Int = dao.count()

    override fun dropOldestEvents(count: Int) {
        if (count > 0) dao.deleteOldest(count)
    }

    override fun oldestEvents(limit: Int): List<StoredEvent> =
        dao.oldest(limit).map { StoredEvent(it.id, it.sessionId, it.json, it.attempts) }

    override fun deleteEvents(ids: List<Long>) {
        ids.chunked(MAX_VARS).forEach { dao.delete(it) }
    }

    override fun recordFailedAttempt(ids: List<Long>, parkAfter: Int) {
        db.runInTransaction(Runnable {
            ids.chunked(MAX_VARS).forEach {
                dao.incrementAttempts(it)
                dao.parkIfExhausted(it, parkAfter)
            }
        })
    }

    override fun unparkEvents(maxCycles: Int): Int {
        var deleted = 0
        db.runInTransaction(Runnable {
            dao.countParkCycle()
            deleted = dao.deleteExhaustedParked(maxCycles)
            dao.unparkAll()
        })
        return deleted
    }

    override fun deleteAllEvents() = dao.deleteAll()

    override fun sessionIdsWithEvents(): Set<String> = dao.sessionIds().toSet()

    override fun putSession(id: String, startedAt: Long, json: String) {
        dao.putSession(SessionEntity(id, startedAt, json))
    }

    override fun session(id: String): String? = dao.session(id)

    override fun recentSessions(limit: Int): List<String> = dao.recentSessions(limit)

    override fun deleteSessionsExcept(keep: Set<String>) {
        val drop = dao.sessionIdsStored().filter { it !in keep }
        drop.chunked(MAX_VARS).forEach { dao.deleteSessions(it) }
    }

    /**
     * Batches queued by SDK 1.10 or older (`pending_events`), removed from
     * that table: the engine re-queues their events under their sessions.
     */
    fun drainLegacyBatches(): List<PriorBatch> {
        val legacy = db.eventDao()
        val out = ArrayList<PriorBatch>()
        try {
            while (true) {
                val rows = legacy.getOldest(LEGACY_CHUNK)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val session = EngineJson.parseObject(row.sessionJson)
                    @Suppress("UNCHECKED_CAST")
                    val events = (try { EngineJson.parse(row.eventsJson) } catch (e: Exception) { null }) as? List<Map<String, Any?>>
                    if (events.isNullOrEmpty()) continue
                    out += PriorBatch((session?.get("sessionId") as? String)?.takeIf { it.isNotEmpty() }, session, events)
                }
                legacy.deleteByIds(rows.map { it.id })
            }
        } catch (t: Throwable) {
            Logger.w("Unilitix: batches from an older SDK version could not be read (${t.javaClass.simpleName}); dropped")
            try { legacy.deleteAll() } catch (_: Throwable) {}
        }
        return out
    }

    private companion object {
        /** SQLite's bound-variable limit is 999 on old devices. */
        const val MAX_VARS = 500
        const val LEGACY_CHUNK = 50
    }
}
