package io.unilitix.sdk.storage;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class EventDatabase_Impl extends EventDatabase {
  private volatile EventDao _eventDao;

  private volatile ScreenshotDao _screenshotDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(3) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_json` TEXT NOT NULL, `events_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `retry_count` INTEGER NOT NULL, `captured_offline` INTEGER NOT NULL, `network_at_capture` TEXT NOT NULL, `sync_attempts` INTEGER NOT NULL, `sync_failed_batches` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_screenshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `screen_name` TEXT NOT NULL, `viewport_width` INTEGER NOT NULL, `viewport_height` INTEGER NOT NULL, `captured_at` INTEGER NOT NULL, `image_bytes` BLOB NOT NULL, `created_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '1e454e08de6bee04886e9fd1dc417d62')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `pending_events`");
        db.execSQL("DROP TABLE IF EXISTS `pending_screenshots`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsPendingEvents = new HashMap<String, TableInfo.Column>(9);
        _columnsPendingEvents.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("session_json", new TableInfo.Column("session_json", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("events_json", new TableInfo.Column("events_json", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("created_at", new TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("retry_count", new TableInfo.Column("retry_count", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("captured_offline", new TableInfo.Column("captured_offline", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("network_at_capture", new TableInfo.Column("network_at_capture", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("sync_attempts", new TableInfo.Column("sync_attempts", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingEvents.put("sync_failed_batches", new TableInfo.Column("sync_failed_batches", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysPendingEvents = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesPendingEvents = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoPendingEvents = new TableInfo("pending_events", _columnsPendingEvents, _foreignKeysPendingEvents, _indicesPendingEvents);
        final TableInfo _existingPendingEvents = TableInfo.read(db, "pending_events");
        if (!_infoPendingEvents.equals(_existingPendingEvents)) {
          return new RoomOpenHelper.ValidationResult(false, "pending_events(io.unilitix.sdk.storage.PendingEvent).\n"
                  + " Expected:\n" + _infoPendingEvents + "\n"
                  + " Found:\n" + _existingPendingEvents);
        }
        final HashMap<String, TableInfo.Column> _columnsPendingScreenshots = new HashMap<String, TableInfo.Column>(9);
        _columnsPendingScreenshots.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("session_id", new TableInfo.Column("session_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("ordinal", new TableInfo.Column("ordinal", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("screen_name", new TableInfo.Column("screen_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("viewport_width", new TableInfo.Column("viewport_width", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("viewport_height", new TableInfo.Column("viewport_height", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("captured_at", new TableInfo.Column("captured_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("image_bytes", new TableInfo.Column("image_bytes", "BLOB", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPendingScreenshots.put("created_at", new TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysPendingScreenshots = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesPendingScreenshots = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoPendingScreenshots = new TableInfo("pending_screenshots", _columnsPendingScreenshots, _foreignKeysPendingScreenshots, _indicesPendingScreenshots);
        final TableInfo _existingPendingScreenshots = TableInfo.read(db, "pending_screenshots");
        if (!_infoPendingScreenshots.equals(_existingPendingScreenshots)) {
          return new RoomOpenHelper.ValidationResult(false, "pending_screenshots(io.unilitix.sdk.storage.PendingScreenshot).\n"
                  + " Expected:\n" + _infoPendingScreenshots + "\n"
                  + " Found:\n" + _existingPendingScreenshots);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "1e454e08de6bee04886e9fd1dc417d62", "34c4fe76394d15dacf23bfdc179673f9");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "pending_events","pending_screenshots");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `pending_events`");
      _db.execSQL("DELETE FROM `pending_screenshots`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(EventDao.class, EventDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(ScreenshotDao.class, ScreenshotDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public EventDao eventDao() {
    if (_eventDao != null) {
      return _eventDao;
    } else {
      synchronized(this) {
        if(_eventDao == null) {
          _eventDao = new EventDao_Impl(this);
        }
        return _eventDao;
      }
    }
  }

  @Override
  public ScreenshotDao screenshotDao() {
    if (_screenshotDao != null) {
      return _screenshotDao;
    } else {
      synchronized(this) {
        if(_screenshotDao == null) {
          _screenshotDao = new ScreenshotDao_Impl(this);
        }
        return _screenshotDao;
      }
    }
  }
}
