package io.unilitix.sdk.flush

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.unilitix.sdk.Unilitix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The background safety net (scheduled when the app goes to the background,
 * with a network constraint): asks the running SDK to upload what is queued
 * and waits for that upload cycle, at most [WAIT_MS]. All the rules (consent,
 * 401/403 latch, backoff window, retry classification, parking) are the
 * engine's; the worker never retries on its own (it always reports success,
 * so WorkManager's retry never multiplies requests).
 */
internal class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sdk = Unilitix.instance ?: return Result.success()
        withContext(Dispatchers.IO) {
            try {
                sdk.flushForWorker(WAIT_MS)
            } catch (_: Throwable) {
                // Never fail the host's WorkManager.
            }
        }
        return Result.success()
    }

    companion object {
        const val WAIT_MS = 30_000L
    }
}
