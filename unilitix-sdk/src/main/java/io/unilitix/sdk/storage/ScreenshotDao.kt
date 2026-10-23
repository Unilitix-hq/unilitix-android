package io.unilitix.sdk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface ScreenshotDao {

    @Insert
    suspend fun insert(screenshot: PendingScreenshot): Long

    @Query("SELECT * FROM pending_screenshots ORDER BY created_at ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<PendingScreenshot>

    @Query("DELETE FROM pending_screenshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_screenshots")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_screenshots")
    suspend fun count(): Int

    @Query("DELETE FROM pending_screenshots WHERE id IN (SELECT id FROM pending_screenshots ORDER BY created_at ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    /** Id and encoded size of every queued screenshot, newest first (for [ScreenshotQueueCap]). */
    @Query("SELECT id, LENGTH(image_bytes) AS bytes FROM pending_screenshots ORDER BY created_at DESC, id DESC")
    suspend fun sizesNewestFirst(): List<ScreenshotSize>

    @Query("DELETE FROM pending_screenshots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE pending_screenshots SET session_id = :serverSessionId WHERE session_id = :localSessionId")
    suspend fun remapSessionId(localSessionId: String, serverSessionId: String)
}
