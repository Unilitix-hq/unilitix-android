package io.unilitix.sdk

import io.unilitix.sdk.core.EventBuffer
import io.unilitix.sdk.core.UnilitixEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBufferTest {

    @Test
    fun `drain returns all events and clears buffer`() = runTest {
        val buffer = EventBuffer(flushBatchSize = 100, onFlushNeeded = {})
        buffer.emit(UnilitixEvent("TAP"))
        buffer.emit(UnilitixEvent("NAVIGATE"))

        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals(0, buffer.size())
    }

    @Test
    fun `flush callback triggered when batch size reached`() = runTest {
        var flushCount = 0
        val buffer = EventBuffer(flushBatchSize = 3, onFlushNeeded = { flushCount++ })

        buffer.emit(UnilitixEvent("E1"))
        buffer.emit(UnilitixEvent("E2"))
        assertEquals(0, flushCount)
        buffer.emit(UnilitixEvent("E3"))
        assertEquals(1, flushCount)
    }

    @Test
    fun `empty drain returns empty list`() = runTest {
        val buffer = EventBuffer(flushBatchSize = 100, onFlushNeeded = {})
        assertTrue(buffer.drain().isEmpty())
    }
}
