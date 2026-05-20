package io.unilitix.sdk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface EventDao {

    @Insert
    suspend fun insert(event: PendingEvent): Long

    @Query("SELECT * FROM pending_events ORDER BY created_at ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<PendingEvent>

    @Query("DELETE FROM pending_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_events SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    @Query("UPDATE pending_events SET sync_attempts = sync_attempts + 1 WHERE id = :id")
    suspend fun incrementSyncAttempts(id: Long)

    @Query("UPDATE pending_events SET sync_failed_batches = sync_failed_batches + 1 WHERE id = :id")
    suspend fun incrementSyncFailedBatches(id: Long)

    @Query("SELECT COUNT(*) FROM pending_events")
    suspend fun count(): Int

    @Query("DELETE FROM pending_events WHERE id IN (SELECT id FROM pending_events ORDER BY created_at ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}
