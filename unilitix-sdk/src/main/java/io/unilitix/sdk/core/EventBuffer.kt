package io.unilitix.sdk.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class UnilitixEvent(
    val type: String,
    val screen: String? = null,
    val properties: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val capturedOffline: Boolean = false,
    val networkAtCapture: String = "UNKNOWN"
)

internal class EventBuffer(
    private val flushBatchSize: Int,
    private val onFlushNeeded: suspend () -> Unit
) {
    private val buffer = mutableListOf<UnilitixEvent>()
    private val mutex = Mutex()

    suspend fun emit(event: UnilitixEvent) {
        val shouldFlush = mutex.withLock {
            buffer.add(event)
            buffer.size >= flushBatchSize
        }
        if (shouldFlush) {
            onFlushNeeded()
        }
    }

    suspend fun drain(): List<UnilitixEvent> = mutex.withLock {
        val events = buffer.toList()
        buffer.clear()
        events
    }

    suspend fun size(): Int = mutex.withLock { buffer.size }
}
