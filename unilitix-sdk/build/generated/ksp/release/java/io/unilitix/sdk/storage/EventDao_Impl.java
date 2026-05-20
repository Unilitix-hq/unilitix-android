package io.unilitix.sdk.storage;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class EventDao_Impl implements EventDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<PendingEvent> __insertionAdapterOfPendingEvent;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  private final SharedSQLiteStatement __preparedStmtOfIncrementRetryCount;

  private final SharedSQLiteStatement __preparedStmtOfIncrementSyncAttempts;

  private final SharedSQLiteStatement __preparedStmtOfIncrementSyncFailedBatches;

  private final SharedSQLiteStatement __preparedStmtOfDeleteOldest;

  public EventDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfPendingEvent = new EntityInsertionAdapter<PendingEvent>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `pending_events` (`id`,`session_json`,`events_json`,`created_at`,`retry_count`,`captured_offline`,`network_at_capture`,`sync_attempts`,`sync_failed_batches`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PendingEvent entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getSessionJson());
        statement.bindString(3, entity.getEventsJson());
        statement.bindLong(4, entity.getCreatedAt());
        statement.bindLong(5, entity.getRetryCount());
        final int _tmp = entity.getCapturedOffline() ? 1 : 0;
        statement.bindLong(6, _tmp);
        statement.bindString(7, entity.getNetworkAtCapture());
        statement.bindLong(8, entity.getSyncAttempts());
        statement.bindLong(9, entity.getSyncFailedBatches());
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM pending_events WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfIncrementRetryCount = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE pending_events SET retry_count = retry_count + 1 WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfIncrementSyncAttempts = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE pending_events SET sync_attempts = sync_attempts + 1 WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfIncrementSyncFailedBatches = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE pending_events SET sync_failed_batches = sync_failed_batches + 1 WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteOldest = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM pending_events WHERE id IN (SELECT id FROM pending_events ORDER BY created_at ASC LIMIT ?)";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final PendingEvent event, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfPendingEvent.insertAndReturnId(event);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object incrementRetryCount(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfIncrementRetryCount.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfIncrementRetryCount.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object incrementSyncAttempts(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfIncrementSyncAttempts.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfIncrementSyncAttempts.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object incrementSyncFailedBatches(final long id,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfIncrementSyncFailedBatches.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfIncrementSyncFailedBatches.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteOldest(final int count, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteOldest.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, count);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteOldest.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object getOldest(final int limit,
      final Continuation<? super List<PendingEvent>> $completion) {
    final String _sql = "SELECT * FROM pending_events ORDER BY created_at ASC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, limit);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<PendingEvent>>() {
      @Override
      @NonNull
      public List<PendingEvent> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSessionJson = CursorUtil.getColumnIndexOrThrow(_cursor, "session_json");
          final int _cursorIndexOfEventsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "events_json");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retry_count");
          final int _cursorIndexOfCapturedOffline = CursorUtil.getColumnIndexOrThrow(_cursor, "captured_offline");
          final int _cursorIndexOfNetworkAtCapture = CursorUtil.getColumnIndexOrThrow(_cursor, "network_at_capture");
          final int _cursorIndexOfSyncAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "sync_attempts");
          final int _cursorIndexOfSyncFailedBatches = CursorUtil.getColumnIndexOrThrow(_cursor, "sync_failed_batches");
          final List<PendingEvent> _result = new ArrayList<PendingEvent>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PendingEvent _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpSessionJson;
            _tmpSessionJson = _cursor.getString(_cursorIndexOfSessionJson);
            final String _tmpEventsJson;
            _tmpEventsJson = _cursor.getString(_cursorIndexOfEventsJson);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final boolean _tmpCapturedOffline;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfCapturedOffline);
            _tmpCapturedOffline = _tmp != 0;
            final String _tmpNetworkAtCapture;
            _tmpNetworkAtCapture = _cursor.getString(_cursorIndexOfNetworkAtCapture);
            final int _tmpSyncAttempts;
            _tmpSyncAttempts = _cursor.getInt(_cursorIndexOfSyncAttempts);
            final int _tmpSyncFailedBatches;
            _tmpSyncFailedBatches = _cursor.getInt(_cursorIndexOfSyncFailedBatches);
            _item = new PendingEvent(_tmpId,_tmpSessionJson,_tmpEventsJson,_tmpCreatedAt,_tmpRetryCount,_tmpCapturedOffline,_tmpNetworkAtCapture,_tmpSyncAttempts,_tmpSyncFailedBatches);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object count(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM pending_events";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
