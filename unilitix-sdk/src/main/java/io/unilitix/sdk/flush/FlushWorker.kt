package io.unilitix.sdk.flush

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.unilitix.sdk.Unilitix
import io.unilitix.sdk.context.isWifi
import io.unilitix.sdk.network.ApiResult
import io.unilitix.sdk.network.RetryPolicy
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.util.Logger
import org.json.JSONObject

internal class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sdk = try {
            Unilitix.instance ?: return Result.success()
        } catch (e: Exception) {
            return Result.success()
        }

        val db = EventDatabase.getInstance(applicationContext)
        val dao = db.eventDao()

        val pending = dao.getOldest(BATCH_LIMIT)
        if (pending.isEmpty()) {
            flushScreenshots(sdk, db)
            return Result.success()
        }

        Logger.d("FlushWorker: processing ${pending.size} pending batches")

        var allSucceeded = true

        for (event in pending) {
            dao.incrementSyncAttempts(event.id)
            val syncAttempts = event.syncAttempts + 1

            val result = RetryPolicy.withRetry {
                sdk.apiClient.ingest(buildPayload(event, syncAttempts))
            }

            when (result) {
                is ApiResult.Success -> {
                    dao.deleteById(event.id)
                    Logger.d("FlushWorker: batch ${event.id} sent (attempt $syncAttempts)")
                    val sessionId = result.sessionId
                    if (sessionId.isNotEmpty()) {
                        val snapshots = sdk.snapshotBuffer.drain()
                        if (snapshots.isNotEmpty()) {
                            sdk.apiClient.uploadSnapshots(sessionId, snapshots)
                        }
                    }
                }
                is ApiResult.ClientError -> {
                    dao.deleteById(event.id)
                    Logger.w("FlushWorker: batch ${event.id} dropped (client error ${result.code})")
                }
                else -> {
                    dao.incrementRetryCount(event.id)
                    dao.incrementSyncFailedBatches(event.id)
                    Logger.w("FlushWorker: batch ${event.id} failed attempt $syncAttempts, will retry")
                    allSucceeded = false
                }
            }
        }

        flushScreenshots(sdk, db)

        return if (allSucceeded) Result.success() else Result.retry()
    }

    private suspend fun flushScreenshots(sdk: io.unilitix.sdk.UnilitixInternal, db: EventDatabase) {
        if (!sdk.config.captureScreenshots) return

        val screenshotDao = db.screenshotDao()
        val pending = screenshotDao.getOldest(SCREENSHOT_BATCH_LIMIT)
        if (pending.isEmpty()) return

        if (sdk.config.uploadScreenshotsOnWifiOnly && !isWifi(applicationContext)) {
            Logger.d("FlushWorker: skipping ${pending.size} screenshots — not on WiFi")
            return
        }

        try {
            sdk.apiClient.uploadScreenshots(pending)
            screenshotDao.deleteByIds(pending.map { it.id })
            Logger.d("FlushWorker: uploaded and deleted ${pending.size} screenshots")
        } catch (e: Exception) {
            Logger.w("FlushWorker: screenshot upload error: ${e.message}")
        }
    }

    // Injects persisted sync counters into the session JSON at upload time.
    // syncAttempts/syncFailedBatches are written by the worker and not known when the batch was first serialized.
    private fun buildPayload(event: PendingEvent, syncAttempts: Int): String {
        return try {
            val sessionObj = JSONObject(event.sessionJson)
            sessionObj.put("syncAttempts", syncAttempts)
            sessionObj.put("syncFailedBatches", event.syncFailedBatches)
            """{"session":$sessionObj,"events":${event.eventsJson}}"""
        } catch (e: Exception) {
            Logger.w("FlushWorker: failed to inject sync stats: ${e.message}")
            """{"session":${event.sessionJson},"events":${event.eventsJson}}"""
        }
    }

    companion object {
        private const val BATCH_LIMIT = 50
        private const val SCREENSHOT_BATCH_LIMIT = 100
    }
}
