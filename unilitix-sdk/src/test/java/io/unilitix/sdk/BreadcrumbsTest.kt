package io.unilitix.sdk

import io.unilitix.sdk.core.Breadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbsTest {

    @Test
    fun `keeps last 50 entries`() {
        val breadcrumbs = Breadcrumbs()
        repeat(60) { breadcrumbs.add("TAP", "Screen$it") }
        val snapshot = breadcrumbs.snapshot()
        assertEquals(50, snapshot.size)
        assertEquals("Screen59", snapshot.last().screen)
        assertEquals("Screen10", snapshot.first().screen)
    }

    @Test
    fun `message rides along and lastScreen skips crumbs without one`() {
        val breadcrumbs = Breadcrumbs()
        breadcrumbs.add("NAVIGATE", "Checkout")
        breadcrumbs.add("custom", null, "payment started")
        assertEquals("Checkout", breadcrumbs.lastScreen())
        val wire = breadcrumbs.toWire()
        assertEquals("payment started", wire.last()["message"])
        assertEquals(false, wire.first().containsKey("message"))
    }

    @Test
    fun `snapshot returns copy`() {
        val breadcrumbs = Breadcrumbs()
        breadcrumbs.add("NAVIGATE", "Home")
        val snapshot1 = breadcrumbs.snapshot()
        breadcrumbs.add("TAP", "Home")
        val snapshot2 = breadcrumbs.snapshot()
        assertEquals(1, snapshot1.size)
        assertEquals(2, snapshot2.size)
    }

    @Test
    fun `empty snapshot is empty`() {
        val breadcrumbs = Breadcrumbs()
        assertTrue(breadcrumbs.snapshot().isEmpty())
    }
}
