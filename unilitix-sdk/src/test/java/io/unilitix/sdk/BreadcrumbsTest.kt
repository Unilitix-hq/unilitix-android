package io.unilitix.sdk

import io.unilitix.sdk.core.Breadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbsTest {

    @Test
    fun `keeps last 10 entries`() {
        val breadcrumbs = Breadcrumbs()
        repeat(15) { breadcrumbs.add("TAP", "Screen$it") }
        val snapshot = breadcrumbs.snapshot()
        assertEquals(10, snapshot.size)
        assertEquals("Screen14", snapshot.last().screen)
        assertEquals("Screen5", snapshot.first().screen)
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
