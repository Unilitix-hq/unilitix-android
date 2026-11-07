package io.unilitix.sdk.engine

import io.unilitix.sdk.UnilitixConsent
import io.unilitix.sdk.util.Logger

/**
 * The public API's entry point into the engine, with the contract's pre-init
 * buffer: calls made before the engine is ready (before `init`, or while
 * `init` finishes off the main thread) are kept, at most
 * [PRE_INIT_BUFFER_SIZE] with the oldest dropped, and replayed in call order
 * right after the first session has started.
 *
 * One instance per process (`Unilitix.core`); the behaviour-fixture runner
 * makes one per simulated process.
 */
internal class UnilitixCore {

    private val lock = Any()
    private val buffer = ArrayDeque<(Engine) -> Unit>()

    @Volatile
    var engine: Engine? = null
        private set

    /** Calls dropped from the full pre-init buffer (for the log line at start). */
    private var dropped = 0

    val isStarted: Boolean get() = engine != null

    /**
     * Starts [e] and replays the buffered calls into it. A second start is
     * ignored (the contract: a second init in one process is a no-op).
     */
    fun start(e: Engine, inputs: StartInputs = StartInputs()): Boolean {
        synchronized(lock) {
            if (engine != null) return false
            e.start(inputs)
            val calls = buffer.toList()
            buffer.clear()
            // Every call below only enqueues onto the engine's executor, in
            // order, after start's own task; nothing new can slip in between
            // because callers take the same lock until `engine` is published.
            for (c in calls) {
                try { c(e) } catch (t: Throwable) { Logger.w("Unilitix: buffered call failed: ${t.javaClass.simpleName}") }
            }
            if (dropped > 0) Logger.w("Unilitix: $dropped calls made before init were dropped (buffer holds $PRE_INIT_BUFFER_SIZE)")
            dropped = 0
            engine = e
        }
        return true
    }

    /** Runs [call] on the engine, or buffers it until [start]. Never throws. */
    fun dispatch(call: (Engine) -> Unit) {
        try {
            engine?.let { call(it); return }
            synchronized(lock) {
                val e = engine
                if (e == null) {
                    buffer.addLast(call)
                    while (buffer.size > PRE_INIT_BUFFER_SIZE) {
                        buffer.removeFirst()
                        dropped++
                    }
                    return
                }
                call(e)
            }
        } catch (t: Throwable) {
            Logger.w("Unilitix: call failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun track(name: String, properties: Map<*, *>?) = dispatch { it.track(name, properties) }
    fun screen(name: String) = dispatch { it.screen(name) }
    fun identify(userId: String, traits: Map<*, *>?) = dispatch { it.identify(userId, traits) }
    fun reset() = dispatch { it.reset() }
    fun setConsent(value: UnilitixConsent) = dispatch { it.setConsent(value) }
    fun flush() = dispatch { it.flush() }
    fun onBackground() = dispatch { it.onBackground() }
    fun onForeground() = dispatch { it.onForeground() }
    fun onNetworkChanged() = dispatch { it.onNetworkChanged() }
    fun recordError(report: ErrorReport) = dispatch { it.recordError(report) }
    fun addBreadcrumb(message: String, category: String) = dispatch { it.addBreadcrumb(message, category) }
    fun setWrapper(name: String, version: String) = dispatch { it.setWrapper(name, version) }
    fun recordInteraction(type: String, fields: Map<String, Any?>) = dispatch { it.recordInteraction(type, fields) }
    fun startNewSession() = dispatch { it.startNewSession() }
    fun endCurrentSession() = dispatch { it.endCurrentSession() }

    /**
     * A fatal error: recorded and uploaded within [budgetMs] when the engine
     * runs; buffered (and so lost if the process dies now) before that.
     */
    fun recordFatal(report: ErrorReport, budgetMs: Long): Boolean {
        val e = engine
        if (e == null) {
            recordError(report)
            return false
        }
        return try { e.recordFatalAndWait(report, budgetMs) } catch (t: Throwable) { false }
    }

    companion object {
        /** Contract `preInitBufferSize`. */
        const val PRE_INIT_BUFFER_SIZE = 200
    }
}
