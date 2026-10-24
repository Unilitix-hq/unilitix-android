package io.unilitix.sdk

import io.unilitix.sdk.capture.ComposeMaskNode
import io.unilitix.sdk.capture.ComposeNodeInfo
import io.unilitix.sdk.capture.ComposeSnapshotNode
import io.unilitix.sdk.capture.ComposeTree
import io.unilitix.sdk.capture.MaskPolicy
import io.unilitix.sdk.capture.MaskRect
import io.unilitix.sdk.capture.MaskableNode
import io.unilitix.sdk.capture.NodeKind
import io.unilitix.sdk.capture.ReplayMasking
import io.unilitix.sdk.capture.SnapshotBuilder
import io.unilitix.sdk.capture.SnapshotNode
import io.unilitix.sdk.capture.SnapshotViewNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jetpack Compose in session replay: semantics trees (as
 * ComposeSemanticsReader copies them out) masked by the view rules. Pure
 * values; the Compose read itself needs a device.
 */
class ComposeMaskingTest {

    private val defaults = UnilitixConfig.Builder().build().let {
        MaskPolicy(it.maskInputs, it.maskInputsInScreenshots, it.maskAllText, it.maskImages)
    }
    private val open = defaults.copy(maskInputs = false, maskInputsInScreenshots = false, maskAllText = false, maskImages = false)

    private fun r(l: Int, t: Int, rr: Int, b: Int) = MaskRect(l, t, rr, b)

    private fun n(
        frame: MaskRect,
        text: String? = null,
        editable: String? = null,
        password: Boolean = false,
        desc: String? = null,
        role: String? = null,
        clickable: Boolean = false,
        unmasked: Boolean = false,
        private: Boolean = false,
        visible: MaskRect? = frame,
        children: List<ComposeNodeInfo> = emptyList(),
    ) = ComposeNodeInfo(
        frame = frame, visible = visible, text = text, editableText = editable,
        isEditable = editable != null, isPassword = password, contentDescription = desc, role = role,
        isClickable = clickable, isUnmasked = unmasked, isPrivate = private, children = children,
    )

    /** A login screen: title, email, password, logo, a button with a label, a canvas balance. */
    private fun screen() = n(
        r(0, 0, 1000, 2000), children = listOf(
            n(r(0, 0, 1000, 100), text = "Welcome back, Ada"),
            n(r(0, 100, 1000, 200), editable = "ada@example.com"),
            n(r(0, 200, 1000, 300), editable = "hunter2", password = true),
            n(r(0, 300, 100, 400), desc = "Logo", role = "Image"),
            n(r(0, 400, 1000, 500), role = "Button", clickable = true, children = listOf(
                n(r(10, 410, 990, 490), text = "Sign in"),
            )),
            n(r(0, 500, 1000, 600), desc = "Balance 1,000.00"),
        )
    )

    /** The host view is at (0, 50) in the screenshot; fully visible. */
    private fun screenshotRects(root: ComposeNodeInfo, policy: MaskPolicy, clip: MaskRect = r(0, 50, 1000, 2050)) =
        ReplayMasking.screenshotRects(ComposeMaskNode(root, 0, 50, clip), policy).toSet()

    // ── classification ────────────────────────────────────────────────────

    @Test
    fun `semantics map to view kinds`() {
        val kids = screen().children
        assertEquals(NodeKind.TEXT, kids[0].kind)
        assertEquals(NodeKind.INPUT, kids[1].kind)
        assertEquals(NodeKind.INPUT, kids[2].kind)
        assertEquals(NodeKind.IMAGE, kids[3].kind)
        assertEquals(NodeKind.OTHER, kids[4].kind)
        assertEquals(NodeKind.TEXT, kids[4].children[0].kind)
        assertEquals(NodeKind.TEXT, kids[5].kind) // content description without image role
        assertEquals(listOf("Text", "TextField", "TextField", "Image", "Button", "Text"), kids.map { it.typeName })
        assertEquals("ComposeNode", n(r(0, 0, 1, 1)).typeName)
        assertEquals("Switch", n(r(0, 0, 1, 1), role = "Switch").typeName)
    }

    @Test
    fun `password marker alone is an input`() {
        assertEquals(NodeKind.INPUT, n(r(0, 0, 1, 1), password = true).kind)
    }

    // ── screenshots ───────────────────────────────────────────────────────

    @Test
    fun `defaults mask text inputs and described nodes, offset into the screenshot`() {
        assertEquals(
            setOf(
                r(0, 50, 1000, 150),      // title
                r(0, 150, 1000, 250),     // email
                r(0, 250, 1000, 350),     // password
                r(10, 460, 990, 540),     // button label
                r(0, 550, 1000, 650),     // canvas balance (content description)
            ),
            screenshotRects(screen(), defaults)
        )
    }

    @Test
    fun `maskAllText off leaves inputs and passwords masked`() {
        assertEquals(setOf(r(0, 150, 1000, 250), r(0, 250, 1000, 350)),
            screenshotRects(screen(), defaults.copy(maskAllText = false)))
        assertEquals(setOf(r(0, 250, 1000, 350)),
            screenshotRects(screen(), defaults.copy(maskAllText = false, maskInputs = false)))
    }

    @Test
    fun `maskImages masks Image role`() {
        assertTrue(r(0, 350, 100, 450) in screenshotRects(screen(), defaults.copy(maskImages = true)))
        assertFalse(r(0, 350, 100, 450) in screenshotRects(screen(), defaults))
    }

    @Test
    fun `unilitixUnmask exempts text in its subtree but never inputs`() {
        val root = n(r(0, 0, 1000, 1000), unmasked = true, children = listOf(
            n(r(0, 0, 100, 100), text = "Heading"),
            n(r(0, 100, 100, 200), editable = "secret", password = true),
        ))
        assertEquals(setOf(r(0, 150, 100, 250)), screenshotRects(root, defaults))
    }

    @Test
    fun `unilitixPrivate masks the whole subtree as one rect even with everything off`() {
        val root = n(r(0, 0, 1000, 1000), children = listOf(
            n(r(0, 0, 500, 500), private = true, unmasked = true, children = listOf(n(r(0, 0, 10, 10), text = "x"))),
        ))
        assertEquals(setOf(r(0, 50, 500, 550)), screenshotRects(root, open))
    }

    @Test
    fun `rects are the clipped bounds and stay inside the host`() {
        val root = n(r(0, 0, 1000, 1000), children = listOf(
            // Scrolled half out of its list: visible part only.
            n(r(0, -50, 1000, 50), visible = r(0, 0, 1000, 50), text = "half"),
            // Scrolled out entirely.
            n(r(0, -200, 1000, -100), visible = null, text = "gone"),
            // Past the host's visible part (host clipped at y = 550).
            n(r(0, 400, 1000, 600), text = "bottom"),
        ))
        assertEquals(setOf(r(0, 50, 1000, 100), r(0, 450, 1000, 550)),
            screenshotRects(root, defaults, clip = r(0, 50, 1000, 550)))
    }

    // ── fail closed ───────────────────────────────────────────────────────

    /** A host view whose Compose tree could not be read in full. */
    private data class Host(override val isUnreadable: Boolean, val unmarked: Boolean = false) : MaskableNode {
        override val kind = NodeKind.OTHER
        override val isVisible = true
        override val isPrivate = false
        override val isMaskedById = false
        override val isUnmasked get() = unmarked
        override val isPassword = false
        override val rect = MaskRect(0, 0, 1080, 1920)
        override val children: List<MaskableNode> = emptyList()
    }

    @Test
    fun `unreadable compose view is masked whole whenever anything inside could be`() {
        val host = Host(isUnreadable = true)
        assertEquals(listOf(host.rect), ReplayMasking.screenshotRects(host, defaults))
        assertEquals(listOf(host.rect), ReplayMasking.screenshotRects(host, open.copy(maskAllText = true)))
        assertEquals(listOf(host.rect), ReplayMasking.screenshotRects(host, open.copy(maskImages = true)))
        assertEquals(listOf(host.rect), ReplayMasking.screenshotRects(host, open.copy(maskInputsInScreenshots = true)))
        // Unmasked still masks it while inputs are masked in screenshots (passwords may be inside).
        assertEquals(listOf(host.rect), ReplayMasking.screenshotRects(Host(true, unmarked = true), defaults))
        assertEquals(emptyList<MaskRect>(),
            ReplayMasking.screenshotRects(Host(true, unmarked = true), defaults.copy(maskInputsInScreenshots = false)))
        assertEquals(emptyList<MaskRect>(), ReplayMasking.screenshotRects(host, open))
        assertEquals(emptyList<MaskRect>(), ReplayMasking.screenshotRects(Host(false), defaults))
    }

    @Test
    fun `failed or truncated trees are unreadable for screenshots`() {
        assertTrue(ComposeTree.FAILED.unreadableForScreenshot)
        assertTrue(ComposeTree(screen(), truncated = true).unreadableForScreenshot)
        assertFalse(ComposeTree(screen()).unreadableForScreenshot)
        assertNull(ComposeTree.FAILED.root)
        assertEquals(SnapshotBuilder.MAX_NODES, ComposeTree.MAX_NODES)
    }

    // ── snapshots ─────────────────────────────────────────────────────────

    private fun snapshot(root: ComposeNodeInfo, policy: MaskPolicy = defaults, maxNodes: Int = SnapshotBuilder.MAX_NODES) =
        SnapshotBuilder.build(ComposeSnapshotNode(root, 0, 50), 1f, policy, maxNodes)!!

    private fun SnapshotNode.flat(): List<SnapshotNode> = listOf(this) + (children?.flatMap { it.flat() } ?: emptyList())

    @Test
    fun `snapshot nodes are typed, offset and masked like views`() {
        val nodes = snapshot(screen()).flat()
        val title = nodes.first { it.type == "Text" && it.bounds.y == 50 }
        assertEquals("••••••• ••••• •••", title.text)
        assertEquals(true, title.redacted)
        val (email, password) = nodes.filter { it.type == "TextField" }
        assertEquals("[MASKED]", email.text)
        assertEquals(false, email.isPassword)
        assertEquals("edit", email.inputType)
        assertEquals("[MASKED]", password.text)
        assertEquals(true, password.isPassword)
        val button = nodes.first { it.type == "Button" }
        assertEquals(true, button.interactive)
        assertEquals("•••• ••", button.children!!.single().text)
        assertEquals(true, nodes.first { it.type == "Image" }.isImage)
        assertTrue(nodes.none { it.text?.contains("Ada") == true || it.text?.contains("1,000") == true })
        assertTrue(nodes.all { it.textColor == null && it.bgColor == null })
    }

    @Test
    fun `snapshot text is shown when masking is off and passwords never`() {
        val nodes = snapshot(screen(), open).flat()
        assertTrue(nodes.any { it.text == "Welcome back, Ada" })
        assertTrue(nodes.any { it.text == "ada@example.com" })
        assertTrue(nodes.any { it.text == "Balance 1,000.00" })
        assertEquals("[MASKED]", nodes.single { it.isPassword == true }.text)
    }

    @Test
    fun `private compose subtree is one masked node in snapshots`() {
        val root = n(r(0, 0, 100, 100), children = listOf(
            n(r(0, 0, 50, 50), private = true, children = listOf(n(r(0, 0, 10, 10), text = "secret"))),
        ))
        val priv = snapshot(root, open).children!!.single()
        assertEquals(true, priv.masked)
        assertNull(priv.children)
        assertNull(priv.text)
    }

    /** A Compose host whose semantics could not be read. */
    private data class SnapHost(override val isUnreadable: Boolean) : SnapshotViewNode {
        override val typeName = "AndroidComposeView"
        override val kind = NodeKind.OTHER
        override val isGone = false
        override val isWebView = false
        override val isVisible = true
        override val isPrivate = false
        override val isMaskedById = false
        override val isUnmasked = false
        override val isPassword = false
        override val frame = MaskRect(0, 0, 100, 100)
        override val text: String? = "never read"
        override val textColor: String? = null
        override val backgroundColor: String? = null
        override val isInteractive = false
        override val children: List<SnapshotViewNode> =
            listOf(ComposeSnapshotNode(ComposeNodeInfo(MaskRect(0, 0, 10, 10), null, text = "leak"), 0, 0))
    }

    @Test
    fun `unreadable compose host is one masked leaf in snapshots`() {
        val node = SnapshotBuilder.build(SnapHost(true), 1f, defaults)!!
        assertEquals(true, node.masked)
        assertNull(node.children)
        assertNull(node.text)
        // Masking off: still a leaf, nothing read.
        val open = SnapshotBuilder.build(SnapHost(true), 1f, open)!!
        assertNull(open.masked)
        assertNull(open.children)
        // Readable: its Compose children are walked.
        assertEquals("••••", SnapshotBuilder.build(SnapHost(false), 1f, defaults)!!.children!!.single().text)
    }

    @Test
    fun `snapshot node cap applies to compose trees`() {
        val wide = n(r(0, 0, 100, 100), children = List(50) { n(r(0, it, 100, it + 1), text = "t$it") })
        assertEquals(10, snapshot(wide, maxNodes = 10).nodeCount)
    }
}
