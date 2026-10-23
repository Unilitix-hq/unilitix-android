package io.unilitix.sdk.flush

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.unilitix.sdk.Unilitix
import io.unilitix.sdk.context.isWifi
import io.unilitix.sdk.network.ApiResult
import io.unilitix.sdk.network.UploadOutcome
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.util.Logger
import org.json.JSONObject

/**
 * One flush cycle. Per the shared SDK contract every request is made once:
 * a retryable failure (no response, 408, 429, 5xx) keeps the data, opens a
 * backoff window in [io.unilitix.sdk.network.UploadGate] and ends the cycle;
 * a delayed flush is scheduled for when the window closes. Other 4xx drop
 * that batch. 401/403 pauses uploads for the rest of the process and keeps
 * everything queued. The worker always reports success to WorkManager, so
 * WorkManager's own retry never multiplies requests.
 */
internal class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sdk = try {
            Unilitix.instance ?: return Result.success()
        } catch (e: Exception) {
            return Result.success()
        }

        // Consent gate: nothing leaves the device unless consent is granted.
        // Pending batches and screenshots stay queued; Unilitix.setConsent(GRANTED)
        // schedules this worker again, DENIED purges the queue.
        if (!sdk.consent.canUpload) {
            Logger.d("FlushWorker: consent ${sdk.consent.state.value} — holding queue, nothing uploaded")
            return Result.success()
        }

        val gate = sdk.uploadGate
        if (gate.authRejected) {
            Logger.d("FlushWorker: API key rejected earlier — uploads paused until the app restarts, queue kept")
            return Result.success()
        }
        val wait = gate.remainingBackoffMs()
        if (wait > 0) {
            Logger.d("FlushWorker: backing off — next attempt in ${wait}ms")
            FlushScheduler(applicationContext).scheduleRetry(wait)
            return Result.success()
        }

        val db = EventDatabase.getInstance(applicationContext)
        val dao = db.eventDao()

        val pending = dao.getOldest(BATCH_LIMIT)
        if (pending.isNotEmpty()) Logger.d("FlushWorker: processing ${pending.size} pending batches")

        // Strictly one batch in flight, oldest first. The first retryable
        // failure ends the cycle: every later batch would meet the same fate.
        for (event in pending) {
            // Consent can change mid-run: re-check before every upload.
            if (!sdk.consent.canUpload) {
                Logger.d("FlushWorker: consent changed — stopping upload")
                return Result.success()
            }
            if (gate.isPaused) break

            dao.incrementSyncAttempts(event.id)
            val syncAttempts = event.syncAttempts + 1

            val result = sdk.apiClient.ingest(buildPayload(event, syncAttempts))

            when (gate.note(result, "Batch ${event.id}")) {
                UploadOutcome.SENT -> {
                    dao.deleteById(event.id)
                    Logger.d("FlushWorker: batch ${event.id} sent (attempt $syncAttempts)")
                    val serverSessionId = (result as ApiResult.Success).sessionId
                    if (serverSessionId.isNotEmpty()) {
                        uploadSnapshots(sdk, serverSessionId)
                        try {
                            val localSessionId = JSONObject(event.sessionJson).optString("sessionId", "")
                            if (localSessionId.isNotEmpty()) {
                                db.screenshotDao().remapSessionId(localSessionId, serverSessionId)
                            }
                        } catch (e: Exception) {
                            Logger.w("FlushWorker: failed to remap screenshot session IDs: ${e.message}")
                        }
                    }
                }
                UploadOutcome.DROPPED -> {
                    // The server will never accept these bytes.
                    dao.deleteById(event.id)
                }
                UploadOutcome.RETRY_LATER -> {
                    dao.incrementRetryCount(event.id)
                    dao.incrementSyncFailedBatches(event.id)
                    break
                }
                UploadOutcome.AUTH_REJECTED -> break // keep the batch on the device
            }
        }

        flushScreenshots(sdk, db)

        val retryIn = gate.remainingBackoffMs()
        if (retryIn > 0 && !gate.authRejected) {
            FlushScheduler(applicationContext).scheduleRetry(retryIn)
        }
        return Result.success()
    }

    private suspend fun uploadSnapshots(sdk: io.unilitix.sdk.UnilitixInternal, serverSessionId: String) {
        val snapshots = if (sdk.consent.canUpload) sdk.snapshotBuffer.drain() else emptyList()
        if (snapshots.isEmpty()) return
        val chunks = snapshots.chunked(SNAPSHOT_CHUNK)
        for ((i, chunk) in chunks.withIndex()) {
            val outcome = if (sdk.uploadGate.isPaused || !sdk.consent.canUpload) {
                UploadOutcome.RETRY_LATER
            } else {
                sdk.uploadGate.note(sdk.apiClient.uploadSnapshots(serverSessionId, chunk), "Snapshots (${chunk.size})")
            }
            if (outcome == UploadOutcome.RETRY_LATER || outcome == UploadOutcome.AUTH_REJECTED) {
                // Keep what was not sent, in order, for the next successful batch.
                sdk.snapshotBuffer.requeue(chunks.drop(i).flatten())
                return
            }
        }
    }

    private suspend fun flushScreenshots(sdk: io.unilitix.sdk.UnilitixInternal, db: EventDatabase) {
        if (!sdk.config.captureScreenshots) return
        if (!sdk.consent.canUpload) return
        if (sdk.uploadGate.isPaused) return

        val screenshotDao = db.screenshotDao()
        val pending = screenshotDao.getOldest(SCREENSHOT_BATCH_LIMIT)
        if (pending.isEmpty()) return

        if (sdk.config.uploadScreenshotsOnWifiOnly && !isWifi(applicationContext)) {
            Logger.d("FlushWorker: skipping ${pending.size} screenshots — not on WiFi")
            return
        }

        try {
            val result = sdk.apiClient.uploadScreenshots(pending)
            if (result.confirmedIds.isNotEmpty()) {
                screenshotDao.deleteByIds(result.confirmedIds.toList())
                Logger.d("FlushWorker: confirmed and deleted ${result.confirmedIds.size}/${pending.size} screenshots")
            }
            result.failure?.let { sdk.uploadGate.note(it, "Screenshots") }
            if (result.confirmedIds.isEmpty()) {
                Logger.d("FlushWorker: 0/${pending.size} screenshots confirmed — will retry next flush")
            }
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
            sessionObj.put("syncDelayMs", System.currentTimeMillis() - event.createdAt)
            """{"session":$sessionObj,"events":${event.eventsJson}}"""
        } catch (e: Exception) {
            Logger.w("FlushWorker: failed to inject sync stats: ${e.message}")
            """{"session":${event.sessionJson},"events":${event.eventsJson}}"""
        }
    }

    companion object {
        private const val BATCH_LIMIT = 50
        private const val SCREENSHOT_BATCH_LIMIT = 100
        private const val SNAPSHOT_CHUNK = 20
    }
}
