package io.unilitix.sdk.storage

import androidx.room.Dao
import androidx.room.Query

/**
 * The 1.10 batch queue (`pending_events`), read once on upgrade and emptied
 * into the engine's queue (see [RoomEngineStore.drainLegacyBatches]).
 */
@Dao
internal interface EventDao {

    @Query("SELECT * FROM pending_events ORDER BY created_at ASC, id ASC LIMIT :limit")
    fun getOldest(limit: Int): List<PendingEvent>

    @Query("DELETE FROM pending_events WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM pending_events")
    fun deleteAll()
}
