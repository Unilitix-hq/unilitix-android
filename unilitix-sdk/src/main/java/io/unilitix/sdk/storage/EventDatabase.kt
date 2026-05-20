package io.unilitix.sdk.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingEvent::class, PendingScreenshot::class], version = 3, exportSchema = false)
internal abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun screenshotDao(): ScreenshotDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pending_events ADD COLUMN captured_offline INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pending_events ADD COLUMN network_at_capture TEXT NOT NULL DEFAULT 'UNKNOWN'")
                database.execSQL("ALTER TABLE pending_events ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pending_events ADD COLUMN sync_failed_batches INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "unilitix_events.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
