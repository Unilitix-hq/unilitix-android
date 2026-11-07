package io.unilitix.sdk.capture

import android.content.Context
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.engine.EngineJson
import io.unilitix.sdk.engine.ErrorReport
import io.unilitix.sdk.engine.IsoTime
import io.unilitix.sdk.engine.PriorBatch
import io.unilitix.sdk.engine.UnilitixCore
import io.unilitix.sdk.util.Logger
import java.io.File
import java.util.UUID

/**
 * Uncaught JVM exceptions. Installed synchronously in `Unilitix.init` (before
 * the rest of the SDK starts on a background thread), so a crash during app
 * start is caught too.
 *
 * The crash is written to a small fallback file first, then recorded through
 * the engine (marks the session crashed, persists the CRASH event and starts
 * an upload), waiting at most the contract's fatal flush budget (2 s) before
 * handing over to the previous handler. When the engine persisted it, the
 * file is deleted; otherwise (the engine was not ready yet, or the disk is
 * stuck) the next launch reports it from the file ([recoverPending]). Both
 * copies carry the same eventId, so the server keeps one.
 */
internal class CrashHandler(
    private val context: Context,
    private val core: UnilitixCore,
    /** False while consent is denied: the crash is passed on, not recorded. */
    private val isEnabled: () -> Boolean = { true },
    /**
     * Tells the native signal handler (API 21-29) that this exception is
     * dealt with, so a SIGABRT that follows it is not reported a second
     * time as a native crash.
     */
    private val markNativeHandled: () -> Unit = { NativeSignalHandler.markExceptionHandled() },
) : Thread.UncaughtExceptionHandler {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Logger.d("CrashHandler: installed")
    }

    /**
     * Puts the previous handler back, only if nobody installed another one on
     * top of this since (then this stays in their chain). Used when init fails.
     */
    fun uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() === this) {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
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
            if (filter != null && !runCatching { filter(throwable) }.getOrDefault(true)) return

            val stackTrace = throwable.stackTraceToString().take(MAX_STACK)
            val type = throwable.javaClass.name
            val message = throwable.message ?: ""
            val eventId = UUID.randomUUID().toString()
            val engine = core.engine

            // The disk copy first: survives whatever happens next.
            val pending = pendingFile(context)
            writePending(pending, engine?.currentSessionId(), eventId, type, message, stackTrace)

            val report = ErrorReport(type, message, stackTrace, fatal = true, platform = PLATFORM, eventId = eventId)
            val persisted = core.recordFatal(report, FATAL_FLUSH_BUDGET_MS)
            if (persisted) pending.delete() else Logger.w("Unilitix: crash kept on disk for the next launch")
        } catch (e: Throwable) {
            Logger.e("CrashHandler: error in crash handler", e)
        } finally {
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writePending(file: File, sessionId: String?, eventId: String, type: String, message: String, stack: String) {
        try {
            val event = linkedMapOf<String, Any?>(
                "type" to "CRASH",
                "eventId" to eventId,
                "timestamp" to IsoTime.format(System.currentTimeMillis()),
                "exceptionType" to type,
                "exceptionMessage" to message,
                "stackTrace" to stack,
                "fatal" to true,
                "title" to "$type: $message".take(200),
                "metadata" to linkedMapOf("fingerprint" to Fingerprint.of(type, stack), "platform" to PLATFORM),
            )
            file.writeText(EngineJson.write(linkedMapOf("sessionId" to sessionId, "event" to event)))
        } catch (e: Throwable) {
            Logger.e("CrashHandler: failed to persist crash to disk", e)
        }
    }

    internal companion object {
        /** Set through [io.unilitix.sdk.Unilitix.setCrashFilter]; false = do not record. */
        @Volatile
        var crashFilter: ((Throwable) -> Boolean)? = null

        /** Contract `fatalFlushBudgetMs`: the longest a crash may hold the crashing thread. */
        const val FATAL_FLUSH_BUDGET_MS = 2_000L
        const val MAX_STACK = 16_000
        const val PLATFORM = "android"

        fun pendingFile(context: Context): File = File(context.cacheDir, "unilitix_crash_pending.json")

        /**
         * The crash a previous process could only write to the fallback file:
         * the 1.11.0 form `{sessionId, event}`, or the 1.10 form
         * `{sessionJson, eventsJson}`. Read and deleted once.
         */
        @Suppress("UNCHECKED_CAST")
        fun recoverPending(context: Context): PriorBatch? {
            val file = pendingFile(context)
            if (!file.isFile) return null
            val text = try { file.readText() } catch (e: Exception) { null }
            file.delete()
            val obj = text?.let { EngineJson.parseObject(it) } ?: return null
            (obj["event"] as? Map<String, Any?>)?.let { event ->
                return PriorBatch(obj["sessionId"] as? String, null, listOf(event))
            }
            val session = (obj["sessionJson"] as? String)?.let { EngineJson.parseObject(it) }
            val events = (obj["eventsJson"] as? String)?.let {
                try { EngineJson.parse(it) as? List<Map<String, Any?>> } catch (e: Exception) { null }
            } ?: return null
            return PriorBatch((session?.get("sessionId") as? String)?.takeIf { it.isNotEmpty() }, session, events)
        }
    }
}
