package io.unilitix.sdk.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.unilitix.sdk.BuildConfig

/**
 * The SDK's on-device queue. Version 4 (1.11.0) adds the engine's tables:
 * `queued_events` (one row per event, with eventId and seq in its JSON),
 * `sessions` (the session records events are uploaded under) and
 * `pending_snapshots` (wireframe snapshots, per session). Batches queued by
 * 1.10 and older in `pending_events` are converted into them on the first
 * start ([RoomEngineStore.drainLegacyBatches]); the table stays for that.
 */
@Database(
    entities = [
        PendingEvent::class, PendingScreenshot::class,
        QueuedEventEntity::class, SessionEntity::class, PendingSnapshot::class,
    ],
    version = 4,
    exportSchema = false,
)
internal abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun screenshotDao(): ScreenshotDao
    abstract fun queueDao(): QueueDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        // v1 → v2: pending_screenshots table was introduced.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_screenshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        session_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        screen_name TEXT NOT NULL,
                        viewport_width INTEGER NOT NULL,
                        viewport_height INTEGER NOT NULL,
                        captured_at INTEGER NOT NULL,
                        image_bytes BLOB NOT NULL,
                        created_at INTEGER NOT NULL
                    )"""
                )
            }
        }

        // v2 → v3: sync/offline tracking columns added to pending_events.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_events ADD COLUMN captured_offline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN network_at_capture TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN sync_failed_batches INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 → v4 (1.11.0): the engine's per-event queue, session records and
        // persisted snapshots; screenshots record their encoding.
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS queued_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        session_id TEXT NOT NULL,
                        json TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        parked INTEGER NOT NULL,
                        park_cycles INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_queued_events_parked_id ON queued_events (parked, id)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        json TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        session_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        json TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_snapshots_session_id ON pending_snapshots (session_id)")
                db.execSQL("ALTER TABLE pending_screenshots ADD COLUMN format TEXT NOT NULL DEFAULT 'webp'")
            }
        }

        fun getInstance(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "unilitix_events.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // A database written by a newer SDK version (an app downgrade)
                    // cannot be opened; starting empty loses only unsent events,
                    // where throwing would crash the host app.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .apply {
                        // Destructive migration only in debug — release builds must never silently
                        // wipe buffered events. Add an explicit migration instead.
                        if (BuildConfig.DEBUG) {
                            fallbackToDestructiveMigration()
                        }
                    }
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
