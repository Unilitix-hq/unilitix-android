package io.unilitix.sdk.capture

import android.content.Context
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.Fingerprint
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
    private val onCrash: () -> Unit,
    /** False while consent is denied: the crash is passed on, not recorded. */
    private val isEnabled: () -> Boolean = { true },
    /**
     * Per-session dedup (contract `maxReportsPerFingerprint`,
     * `maxErrorReportsPerSession`): false when this fingerprint was already
     * reported often enough this session. The crash is still passed on.
     */
    private val admitReport: (fingerprint: String) -> Boolean = { true },
    /**
     * Tells the native signal handler (API 21-29) that this exception is
     * dealt with, so a SIGABRT that follows it is not reported a second
     * time as a native crash.
     */
    private val markNativeHandled: () -> Unit = { NativeSignalHandler.markExceptionHandled() }
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
            // First, even when nothing is recorded (consent denied): this
            // exception is the crash, not the signal that may follow it.
            try { markNativeHandled() } catch (e: Throwable) { /* already crashing */ }
            if (!isEnabled()) return // finally still hands the crash to the original handler
            // A wrapper SDK that already reported this crash itself (React
            // Native's fatal JS error, re-thrown as JavascriptException) asks
            // for it to be passed on unrecorded, so it is not counted twice.
            val filter = crashFilter
            if (filter != null && !runCatching { filter(throwable) }.getOrDefault(true)) {
                onCrash()
                return // finally still hands the crash to the original handler
            }
            onCrash()

            val stackTrace = throwable.stackTraceToString().take(16_000)
            val crumbs = breadcrumbs.toWire()
            val lastScreen = breadcrumbs.lastScreen() ?: ""
            val exType = throwable.javaClass.name
            val title = "$exType: ${throwable.message ?: ""}".take(200)
            val fingerprint = Fingerprint.of(exType, stackTrace)
            if (!admitReport(fingerprint)) {
                Logger.d("CrashHandler: repeat crash $fingerprint suppressed (per-session cap)")
                return // finally still hands the crash to the original handler
            }

            val crashEvent = mapOf(
                "type"             to "CRASH",
                "timestamp"        to isoNow(),
                "exceptionType"    to exType,
                "exceptionMessage" to (throwable.message ?: ""),
                "stackTrace"       to stackTrace,
                "breadcrumbs"      to crumbs,
                // A JVM uncaught exception kills the process: fatal, like the
                // Flutter SDK's native crashes. Title and fingerprint let the
                // server group one bug once across devices.
                "fatal"            to true,
                "title"            to title,
                "metadata"         to mapOf("fingerprint" to fingerprint),
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

            // Insert into the queue database, waiting at most the contract's
            // fatal flush budget (2 s): the disk copy above already survives
            // the process, and the next handler (Crashlytics, the system dialog)
            // must not be held up longer than that.
            val persisted = java.util.concurrent.CountDownLatch(1)
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
                } finally {
                    persisted.countDown()
                }
            }
            persisted.await(FATAL_FLUSH_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)

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

    internal companion object {
        /** Set through [io.unilitix.sdk.Unilitix.setCrashFilter]; false = do not record. */
        @Volatile
        var crashFilter: ((Throwable) -> Boolean)? = null

        /** Contract `fatalFlushBudgetMs`: the longest a crash may hold the crashing thread. */
        const val FATAL_FLUSH_BUDGET_MS = 2_000L
    }
}
