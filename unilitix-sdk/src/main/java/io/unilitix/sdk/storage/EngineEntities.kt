package io.unilitix.sdk.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** One queued event (1.11.0): its session, and its JSON with eventId and seq. */
@Entity(tableName = "queued_events", indices = [Index(value = ["parked", "id"])])
internal data class QueuedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "parked") val parked: Boolean = false,
    @ColumnInfo(name = "park_cycles") val parkCycles: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/** The engine's record of one session (SessionRecord JSON), 1.11.0. */
@Entity(tableName = "sessions")
internal data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "json") val json: String,
)

/** A wireframe snapshot waiting for upload, with the session it belongs to (1.11.0). */
@Entity(tableName = "pending_snapshots", indices = [Index(value = ["session_id"])])
internal data class PendingSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    /** One element of the contract's `snapshots` array, as JSON. */
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Dao
internal interface QueueDao {
    @Insert
    fun insert(e: QueuedEventEntity): Long

    @Query("SELECT COUNT(*) FROM queued_events")
    fun count(): Int

    @Query("DELETE FROM queued_events WHERE id IN (SELECT id FROM queued_events ORDER BY id ASC LIMIT :count)")
    fun deleteOldest(count: Int)

    @Query("SELECT * FROM queued_events WHERE parked = 0 ORDER BY id ASC LIMIT :limit")
    fun oldest(limit: Int): List<QueuedEventEntity>

    @Query("DELETE FROM queued_events WHERE id IN (:ids)")
    fun delete(ids: List<Long>)

    @Query("UPDATE queued_events SET attempts = attempts + 1 WHERE id IN (:ids)")
    fun incrementAttempts(ids: List<Long>)

    @Query("UPDATE queued_events SET parked = 1 WHERE id IN (:ids) AND attempts >= :parkAfter")
    fun parkIfExhausted(ids: List<Long>, parkAfter: Int)

    @Query("UPDATE queued_events SET park_cycles = park_cycles + 1 WHERE parked = 1")
    fun countParkCycle()

    @Query("DELETE FROM queued_events WHERE parked = 1 AND park_cycles >= :maxCycles")
    fun deleteExhaustedParked(maxCycles: Int): Int

    @Query("UPDATE queued_events SET parked = 0, attempts = 0 WHERE parked = 1")
    fun unparkAll()

    @Query("DELETE FROM queued_events")
    fun deleteAll()

    @Query("SELECT DISTINCT session_id FROM queued_events")
    fun sessionIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSession(s: SessionEntity)

    @Query("SELECT json FROM sessions WHERE id = :id")
    fun session(id: String): String?

    @Query("SELECT json FROM sessions ORDER BY started_at DESC LIMIT :limit")
    fun recentSessions(limit: Int): List<String>

    @Query("SELECT id FROM sessions")
    fun sessionIdsStored(): List<String>

    @Query("DELETE FROM sessions WHERE id IN (:ids)")
    fun deleteSessions(ids: List<String>)
}

@Dao
internal interface SnapshotDao {
    @Insert
    fun insert(s: PendingSnapshot): Long

    @Query("SELECT * FROM pending_snapshots ORDER BY id ASC LIMIT :limit")
    fun oldest(limit: Int): List<PendingSnapshot>

    @Query("SELECT COUNT(*) FROM pending_snapshots")
    fun count(): Int

    @Query("DELETE FROM pending_snapshots WHERE id IN (SELECT id FROM pending_snapshots ORDER BY id ASC LIMIT :count)")
    fun deleteOldest(count: Int)

    @Query("DELETE FROM pending_snapshots WHERE id IN (:ids)")
    fun delete(ids: List<Long>)

    @Query("DELETE FROM pending_snapshots")
    fun deleteAll()
}
