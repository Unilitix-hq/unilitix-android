package io.unilitix.sdk.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_screenshots")
internal data class PendingScreenshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "screen_name") val screenName: String,
    @ColumnInfo(name = "viewport_width") val viewportWidth: Int,
    @ColumnInfo(name = "viewport_height") val viewportHeight: Int,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "image_bytes", typeAffinity = ColumnInfo.BLOB) val imageBytes: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingScreenshot) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
