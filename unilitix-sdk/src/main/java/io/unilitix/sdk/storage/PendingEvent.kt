package io.unilitix.sdk.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_events")
internal data class PendingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_json") val sessionJson: String,
    @ColumnInfo(name = "events_json") val eventsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "captured_offline") val capturedOffline: Boolean = false,
    @ColumnInfo(name = "network_at_capture") val networkAtCapture: String = "UNKNOWN",
    @ColumnInfo(name = "sync_attempts") val syncAttempts: Int = 0,
    @ColumnInfo(name = "sync_failed_batches") val syncFailedBatches: Int = 0
)
