package io.unilitix.sdk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Called from the SDK's background threads only (never the main thread). */
@Dao
internal interface ScreenshotDao {

    @Insert
    fun insert(screenshot: PendingScreenshot): Long

    @Query("SELECT * FROM pending_screenshots ORDER BY created_at ASC, id ASC LIMIT :limit")
    fun getOldest(limit: Int): List<PendingScreenshot>

    @Query("DELETE FROM pending_screenshots WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM pending_screenshots")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_screenshots")
    fun count(): Int

    @Query("DELETE FROM pending_screenshots WHERE id IN (SELECT id FROM pending_screenshots ORDER BY created_at ASC LIMIT :count)")
    fun deleteOldest(count: Int)

    /** Id and encoded size of every queued screenshot, newest first (for [ScreenshotQueueCap]). */
    @Query("SELECT id, LENGTH(image_bytes) AS bytes FROM pending_screenshots ORDER BY created_at DESC, id DESC")
    fun sizesNewestFirst(): List<ScreenshotSize>

    @Query("DELETE FROM pending_screenshots WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)
}
