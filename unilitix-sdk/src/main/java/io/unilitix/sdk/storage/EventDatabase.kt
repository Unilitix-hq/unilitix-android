package io.unilitix.sdk.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.unilitix.sdk.BuildConfig

@Database(entities = [PendingEvent::class, PendingScreenshot::class], version = 3, exportSchema = false)
internal abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun screenshotDao(): ScreenshotDao

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

        fun getInstance(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "unilitix_events.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
