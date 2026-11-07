package io.unilitix.sdk.capture

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.unilitix.sdk.util.Logger

/**
 * Live ANR detection (1.11.0), like the iOS SDK's AppHang: a background
 * thread posts a no-op to the main looper every [checkIntervalMs] and, when
 * the main thread has not run it within [timeoutMs], reads the main thread's
 * stack and reports an `ANR` event right away (the process may never come
 * back). One report per stall: the next one needs the main thread to have
 * recovered first.
 *
 * Runs only while the app is in the foreground ([start]/[stop] from the
 * process lifecycle) and never while a debugger is attached (breakpoints
 * stall the main thread). On API 30+ the system's `REASON_ANR` exit record is
 * also read on the next launch; [ProcessExits] skips the exit of a process
 * whose ANR this watchdog already reported ([onReported] records the pid).
 */
internal class AnrWatchdog(
    private val timeoutMs: Long,
    private val onAnr: (message: String, stackTrace: String) -> Unit,
    private val onReported: () -> Unit = {},
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val isDebuggerAttached: () -> Boolean = { Debug.isDebuggerConnected() || Debug.waitingForDebugger() },
) {
    private val checkIntervalMs: Long = (timeoutMs / 5).coerceIn(250L, 1_000L)

    @Volatile private var lastPong = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    private val pong = Runnable { lastPong = SystemClock.uptimeMillis() }

    @Synchronized
    fun start() {
        if (running || timeoutMs <= 0) return
        running = true
        lastPong = SystemClock.uptimeMillis()
        thread = Thread({ loop() }, "unilitix-anr-watchdog").also {
            it.isDaemon = true
            it.priority = Thread.MIN_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var reported = false
        try {
            while (running) {
                mainHandler.post(pong)
                Thread.sleep(checkIntervalMs)
                val blockedFor = SystemClock.uptimeMillis() - lastPong
                if (blockedFor < timeoutMs) {
                    reported = false // recovered: the next stall is a new ANR
                    continue
                }
                if (reported || isDebuggerAttached()) continue
                reported = true
                val stack = mainThreadStack()
                Logger.w("Unilitix: main thread blocked for ${blockedFor}ms (ANR)")
                try {
                    onAnr("Main thread blocked for ${blockedFor} ms", stack)
                    onReported()
                } catch (t: Throwable) {
                    Logger.d("AnrWatchdog: report failed: ${t.message}")
                }
            }
        } catch (_: InterruptedException) {
            // stop()
        } catch (t: Throwable) {
            Logger.d("AnrWatchdog: stopped: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * The main thread's stack in the ART thread-dump form the ANR
         * fingerprint reads (`"main" ...` then `  at a.B.c(B.kt:1)` lines),
         * capped like a crash stack.
         */
        fun mainThreadStack(): String {
            val main = Looper.getMainLooper().thread
            return format(main.name, main.state.name, main.stackTrace)
        }

        fun format(name: String, state: String, frames: Array<StackTraceElement>): String {
            val sb = StringBuilder()
            sb.append('"').append(name).append("\" tid=1 ").append(state)
            for (f in frames) {
                if (sb.length > AnrTrace.MAX_STACK) break
                sb.append("\n  at ").append(f.className).append('.').append(f.methodName).append('(')
                when {
                    f.isNativeMethod -> sb.append("Native method")
                    f.fileName == null -> sb.append("Unknown Source")
                    f.lineNumber >= 0 -> sb.append(f.fileName).append(':').append(f.lineNumber)
                    else -> sb.append(f.fileName)
                }
                sb.append(')')
            }
            return sb.toString().take(AnrTrace.MAX_STACK)
        }
    }
}
