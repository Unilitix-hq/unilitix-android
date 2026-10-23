package io.unilitix.sdk.flush

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.unilitix.sdk.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Enqueues [FlushWorker] as one unique work chain, so at most one flush runs
 * at a time. Backoff is the SDK's own ([io.unilitix.sdk.network.UploadGate]),
 * not WorkManager's: the worker never returns `Result.retry()`; after a
 * retryable failure it calls [scheduleRetry] for when the window closes.
 */
internal class FlushScheduler(private val context: Context) {

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleFlush() {
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(constraints())
            .build()

        // KEEP: a flush already queued (including a delayed retry waiting out
        // a backoff window) covers this request.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )

        Logger.d("FlushScheduler: flush work enqueued")
    }

    /**
     * Runs a flush once [delayMs] has passed (the end of the backoff window).
     * Appended to the unique chain, so it starts after the running worker
     * returns and never overlaps it.
     */
    fun scheduleRetry(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )

        Logger.d("FlushScheduler: retry flush enqueued in ${delayMs}ms")
    }

    fun cancelPending() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val WORK_NAME = "unilitix_flush"
    }
}
