package io.unilitix.sdk.capture

import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.util.Logger
import io.unilitix.sdk.util.Json
import kotlinx.coroutines.runBlocking

internal class CrashHandler(
    private val breadcrumbs: Breadcrumbs,
    private val database: EventDatabase,
    private val getCurrentSessionJson: () -> String?,
    private val onCrash: () -> Unit
) : Thread.UncaughtExceptionHandler {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Logger.d("CrashHandler: installed")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            onCrash()

            val stackTrace = throwable.stackTraceToString()
            val crumbs = breadcrumbs.snapshot().map {
                mapOf("type" to it.type, "screen" to (it.screen ?: ""), "timestamp" to it.timestamp)
            }

            val crashEvent = mapOf(
                "type" to "CRASH",
                "timestamp" to System.currentTimeMillis(),
                "properties" to mapOf(
                    "exception_type" to throwable.javaClass.name,
                    "exception_message" to (throwable.message ?: ""),
                    "stack_trace" to stackTrace,
                    "breadcrumbs" to crumbs
                )
            )

            val sessionJson = getCurrentSessionJson() ?: "{}"
            val eventsJson = Json.toJson(listOf(crashEvent))

            runBlocking {
                try {
                    database.eventDao().insert(
                        PendingEvent(
                            sessionJson = sessionJson,
                            eventsJson = eventsJson
                        )
                    )
                    Logger.d("CrashHandler: crash persisted to DB")
                } catch (e: Exception) {
                    Logger.e("CrashHandler: failed to persist crash", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("CrashHandler: error in crash handler", e)
        } finally {
            originalHandler?.uncaughtException(thread, throwable)
        }
    }
}
