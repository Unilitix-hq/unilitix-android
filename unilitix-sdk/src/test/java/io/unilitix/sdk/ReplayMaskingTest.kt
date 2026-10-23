package io.unilitix.sdk

import io.unilitix.sdk.capture.MaskPolicy
import io.unilitix.sdk.capture.MaskRect
import io.unilitix.sdk.capture.MaskableNode
import io.unilitix.sdk.capture.NodeKind
import io.unilitix.sdk.capture.ReplayMasking
import io.unilitix.sdk.storage.ScreenshotQueueCap
import io.unilitix.sdk.storage.ScreenshotSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayMaskingTest {

    private data class Node(
        val name: String,
        override val kind: NodeKind = NodeKind.OTHER,
        override val isVisible: Boolean = true,
        override val isPrivate: Boolean = false,
        override val isMaskedById: Boolean = false,
        override val isUnmasked: Boolean = false,
        override val isPassword: Boolean = false,
        override val children: List<MaskableNode> = emptyList(),
        val onScreen: Boolean = true,
    ) : MaskableNode {
        override val rect: MaskRect? get() = if (onScreen) MaskRect(name.hashCode(), 0, 0, 0) else null
    }

    private fun rectOf(name: String) = MaskRect(name.hashCode(), 0, 0, 0)

    private val defaults = UnilitixConfig.Builder().build().let {
        MaskPolicy(it.maskInputs, it.maskInputsInScreenshots, it.maskAllText, it.maskImages)
    }

    private fun screen() = Node(
        "root", children = listOf(
            Node("title", NodeKind.TEXT),
            Node("email", NodeKind.INPUT),
            Node("password", NodeKind.INPUT, isPassword = true),
            Node("avatar", NodeKind.IMAGE),
            Node("container"),
        )
    )

    private fun masked(root: MaskableNode, policy: MaskPolicy) =
        ReplayMasking.screenshotRects(root, policy).toSet()

    @Test
    fun `defaults mask all text and inputs but not images`() {
        assertTrue(defaults.maskAllText)
        assertFalse(defaults.maskImages)
        assertEquals(setOf(rectOf("title"), rectOf("email"), rectOf("password")), masked(screen(), defaults))
    }

    @Test
    fun `maskAllText off masks inputs only`() {
        val p = defaults.copy(maskAllText = false)
        assertEquals(setOf(rectOf("email"), rectOf("password")), masked(screen(), p))
    }

    @Test
    fun `passwords stay masked with maskInputs off`() {
        val p = defaults.copy(maskAllText = false, maskInputs = false)
        assertEquals(setOf(rectOf("password")), masked(screen(), p))
    }

    @Test
    fun `maskImages masks images`() {
        val p = defaults.copy(maskAllText = false, maskImages = true)
        assertEquals(setOf(rectOf("email"), rectOf("password"), rectOf("avatar")), masked(screen(), p))
    }

    @Test
    fun `unmask exempts a subtree from text and image masking but never inputs`() {
        val root = Node(
            "root", children = listOf(
                Node("header", isUnmasked = true, children = listOf(
                    Node("heading", NodeKind.TEXT),
                    Node("logo", NodeKind.IMAGE),
                    Node("search", NodeKind.INPUT),
                    Node("secret", NodeKind.TEXT, isMaskedById = true),
                )),
                Node("body", NodeKind.TEXT),
            )
        )
        val p = defaults.copy(maskImages = true)
        assertEquals(setOf(rectOf("search"), rectOf("secret"), rectOf("body")), masked(root, p))
    }

    @Test
    fun `private subtree is one rect even inside an unmasked view`() {
        val root = Node(
            "root", isUnmasked = true, children = listOf(
                Node("card", isPrivate = true, children = listOf(Node("number", NodeKind.TEXT))),
                Node("label", NodeKind.TEXT),
            )
        )
        assertEquals(listOf(rectOf("card")), ReplayMasking.screenshotRects(root, defaults))
    }

    @Test
    fun `hidden and off-screen views are not painted over`() {
        val root = Node(
            "root", children = listOf(
                Node("gone", isVisible = false, children = listOf(Node("inner", NodeKind.TEXT))),
                Node("scrolledAway", NodeKind.TEXT, onScreen = false),
                Node("shown", NodeKind.TEXT),
            )
        )
        assertEquals(listOf(rectOf("shown")), ReplayMasking.screenshotRects(root, defaults))
    }

    @Test
    fun `snapshot text is redacted, inputs masked, unmasked kept`() {
        val t = ReplayMasking.snapshotText("Hello wörld 🙂!", isInput = false, isPassword = false, unmasked = false, policy = defaults)
        assertEquals("••••• ••••• ••", t.text)
        assertTrue(t.redacted)

        val input = ReplayMasking.snapshotText("a@b.c", isInput = true, isPassword = false, unmasked = true, policy = defaults)
        assertEquals("[MASKED]", input.text)

        val kept = ReplayMasking.snapshotText("Pay now", isInput = false, isPassword = false, unmasked = true, policy = defaults)
        assertEquals("Pay now", kept.text)
        assertFalse(kept.redacted)

        val off = ReplayMasking.snapshotText("Pay now", isInput = false, isPassword = false, unmasked = false,
            policy = defaults.copy(maskAllText = false))
        assertEquals("Pay now", off.text)

        val inputOff = ReplayMasking.snapshotText("query", isInput = true, isPassword = false, unmasked = false,
            policy = defaults.copy(maskInputs = false))
        assertEquals("maskAllText still redacts an input", "•••••", inputOff.text)
    }

    @Test
    fun `snapshot images dropped only with maskImages`() {
        assertFalse(ReplayMasking.maskSnapshotImage(unmasked = false, policy = defaults))
        assertTrue(ReplayMasking.maskSnapshotImage(unmasked = false, policy = defaults.copy(maskImages = true)))
        assertFalse(ReplayMasking.maskSnapshotImage(unmasked = true, policy = defaults.copy(maskImages = true)))
    }

    @Test
    fun `redact keeps whitespace and length`() {
        assertEquals("", ReplayMasking.redact(""))
        assertEquals("•• •\n••", ReplayMasking.redact("ab c\nde"))
    }

    // ── Screenshot queue cap ──────────────────────────────────────────────

    @Test
    fun `screenshot queue keeps the newest within count and bytes`() {
        val newestFirst = (10L downTo 1L).map { ScreenshotSize(id = it, bytes = 100) }
        assertEquals(listOf(3L, 2L, 1L), ScreenshotQueueCap.idsToEvict(newestFirst, maxCount = 7, maxBytes = 10_000))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ScreenshotQueueCap.idsToEvict(newestFirst, maxCount = 100, maxBytes = 500))
        assertTrue(ScreenshotQueueCap.idsToEvict(newestFirst, maxCount = 100, maxBytes = 10_000).isEmpty())
    }

    @Test
    fun `screenshot queue evicts oldest first even if a small old frame would fit`() {
        val newestFirst = listOf(ScreenshotSize(3, 300), ScreenshotSize(2, 300), ScreenshotSize(1, 10))
        assertEquals(listOf(2L, 1L), ScreenshotQueueCap.idsToEvict(newestFirst, maxCount = 10, maxBytes = 500))
    }
}
