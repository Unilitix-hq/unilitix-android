package io.unilitix.sdk.capture

import android.content.Context
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.util.Logger
import io.unilitix.sdk.util.Json
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class CrashHandler(
    private val context: Context,
    private val breadcrumbs: Breadcrumbs,
    private val database: EventDatabase,
    private val getCurrentSessionJson: () -> String?,
    private val onCrash: () -> Unit
) : Thread.UncaughtExceptionHandler {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    // Dedicated scope for crash persistence — never blocks the crash thread.
    private val crashScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, _ -> /* already crashing — ignore */ }
    )

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Logger.d("CrashHandler: installed")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            onCrash()

            val stackTrace = throwable.stackTraceToString()
            val crumbList = breadcrumbs.snapshot()
            val crumbs = crumbList.map {
                mapOf("type" to it.type, "screen" to (it.screen ?: ""), "timestamp" to it.timestamp)
            }
            val lastScreen = crumbList.lastOrNull { it.screen != null }?.screen ?: ""

            val crashEvent = mapOf(
                "type"             to "CRASH",
                "timestamp"        to isoNow(),
                "exceptionType"    to throwable.javaClass.name,
                "exceptionMessage" to (throwable.message ?: ""),
                "stackTrace"       to stackTrace,
                "breadcrumbs"      to crumbs,
                "screen"           to lastScreen,
                "x"                to 0.0,
                "y"                to 0.0,
                "capturedOffline"  to false,
                "networkAtCapture" to ""
            )

            val sessionJson = getCurrentSessionJson() ?: "{}"
            val eventsJson = Json.toJson(listOf(crashEvent))

            // Write to disk first — if Room is unavailable during the crash this is the fallback.
            persistCrashToDisk(sessionJson, eventsJson)

            // Fire DB insert without blocking the crash thread.
            crashScope.launch {
                try {
                    database.eventDao().insert(
                        PendingEvent(
                            sessionJson = sessionJson,
                            eventsJson = eventsJson
                        )
                    )
                    // Room insert succeeded — disk copy is now redundant.
                    crashPendingFile().delete()
                    Logger.d("CrashHandler: crash persisted to DB")
                } catch (e: Exception) {
                    Logger.e("CrashHandler: failed to persist crash to DB", e)
                    // Disk copy remains and will be recovered on next launch.
                }
            }

            // Give the coroutine up to 3 seconds before letting the process die.
            Thread.sleep(3_000)

        } catch (e: Exception) {
            Logger.e("CrashHandler: error in crash handler", e)
        } finally {
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun persistCrashToDisk(sessionJson: String, eventsJson: String) {
        try {
            val payload = JSONObject().apply {
                put("sessionJson", sessionJson)
                put("eventsJson", eventsJson)
            }
            crashPendingFile().writeText(payload.toString())
        } catch (e: Exception) {
            Logger.e("CrashHandler: failed to persist crash to disk", e)
        }
    }

    private fun crashPendingFile(): File = File(context.cacheDir, "unilitix_crash_pending.json")

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
