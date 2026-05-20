package io.unilitix.sdk.flush

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

internal class UnilitixWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            FlushWorker::class.java.name -> FlushWorker(appContext, workerParameters)
            else -> null
        }
    }
}
