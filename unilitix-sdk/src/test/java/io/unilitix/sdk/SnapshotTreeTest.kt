package io.unilitix.sdk

import io.unilitix.sdk.capture.Dp
import io.unilitix.sdk.capture.MaskPolicy
import io.unilitix.sdk.capture.MaskRect
import io.unilitix.sdk.capture.NodeKind
import io.unilitix.sdk.capture.SnapshotBounds
import io.unilitix.sdk.capture.SnapshotBuilder
import io.unilitix.sdk.capture.SnapshotNode
import io.unilitix.sdk.capture.SnapshotScheduler
import io.unilitix.sdk.capture.SnapshotViewNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTreeTest {

    /** A plain-value view for the walk; frames are in pixels. */
    private data class V(
        override val typeName: String,
        override val kind: NodeKind = NodeKind.OTHER,
        override val frame: MaskRect = MaskRect(0, 0, 300, 300),
        override val text: String? = null,
        override val isVisible: Boolean = true,
        override val isGone: Boolean = false,
        override val isPrivate: Boolean = false,
        override val isMaskedById: Boolean = false,
        override val isUnmasked: Boolean = false,
        override val isPassword: Boolean = false,
        override val isWebView: Boolean = false,
        override val textColor: String? = null,
        override val backgroundColor: String? = null,
        override val isInteractive: Boolean = false,
        override val children: List<SnapshotViewNode> = emptyList(),
    ) : SnapshotViewNode

    private val defaults = UnilitixConfig.Builder().build().let {
        MaskPolicy(it.maskInputs, it.maskInputsInScreenshots, it.maskAllText, it.maskImages)
    }
    private val open = defaults.copy(maskInputs = false, maskAllText = false, maskImages = false)

    private fun build(root: SnapshotViewNode, policy: MaskPolicy = defaults, density: Float = 1f,
                      maxNodes: Int = SnapshotBuilder.MAX_NODES, maxDepth: Int = SnapshotBuilder.MAX_DEPTH) =
        SnapshotBuilder.build(root, density, policy, maxNodes, maxDepth)

    private fun SnapshotNode.find(type: String): SnapshotNode? =
        if (this.type == type) this else children?.firstNotNullOfOrNull { it.find(type) }

    // ── dp ────────────────────────────────────────────────────────────────

    @Test
    fun `pixels convert to rounded dp`() {
        assertEquals(360, Dp.of(1080, 3f))
        assertEquals(411, Dp.of(1440, 3.5f))   // 411.43
        assertEquals(15, Dp.of(40, 2.625f))    // 15.24
        assertEquals(16, Dp.of(42, 2.625f))    // 16.0
        assertEquals(0, Dp.of(0, 2f))
        assertEquals(-12, Dp.of(-36, 3f))      // scrolled above the window
        assertEquals("bad density falls back to px", 50, Dp.of(50, 0f))
        assertEquals(SnapshotBounds(10, 20, 100, 33), Dp.bounds(MaskRect(30, 60, 330, 160), 3f))
    }

    @Test
    fun `tap positions convert to dp relative to the window origin`() {
        assertEquals(180, Dp.point(540f, 0, 3f))
        assertEquals(100, Dp.point(1300.4f, 1000, 3f))   // split screen: window at x=1000 px
        assertEquals(15, Dp.point(40.5f, 0, 2.625f))     // 15.43
        // Same rounding as snapshot bounds for a pixel-aligned tap.
        assertEquals(Dp.of(1234, 2.75f), Dp.point(1234f, 0, 2.75f))
        assertEquals("bad density falls back to px", 7, Dp.point(7f, 0, Float.NaN))
    }

    @Test
    fun `bounds are emitted in dp`() {
        val root = V("Root", frame = MaskRect(0, 0, 1080, 2400),
            children = listOf(V("Box", frame = MaskRect(90, 300, 390, 600))))
        val tree = build(root, density = 3f)!!
        assertEquals(SnapshotBounds(0, 0, 360, 800), tree.bounds)
        assertEquals(SnapshotBounds(30, 100, 100, 100), tree.find("Box")!!.bounds)
    }

    // ── private views ─────────────────────────────────────────────────────

    @Test
    fun `private and maskedViewIds views become bare masked placeholders`() {
        val secret = V("CardView", isPrivate = true, backgroundColor = "#FFFFFF", isInteractive = true,
            frame = MaskRect(0, 0, 200, 100),
            children = listOf(V("Pan", NodeKind.TEXT, text = "4111 1111 1111 1111")))
        val byId = V("EditText", NodeKind.INPUT, isMaskedById = true, text = "secret", frame = MaskRect(0, 100, 200, 150))
        val tree = build(V("Root", children = listOf(secret, byId)), policy = open, density = 2f)!!

        assertEquals(SnapshotNode(type = "CardView", bounds = SnapshotBounds(0, 0, 100, 50), masked = true),
            tree.find("CardView"))
        assertEquals(SnapshotNode(type = "EditText", bounds = SnapshotBounds(0, 50, 100, 25), masked = true),
            tree.find("EditText"))
        assertNull("children of a private view are never walked", tree.find("Pan"))
    }

    @Test
    fun `private wins inside an unmasked subtree`() {
        val tree = build(V("Root", isUnmasked = true, children = listOf(V("Secret", NodeKind.TEXT, text = "x", isPrivate = true))))!!
        val n = tree.find("Secret")!!
        assertEquals(true, n.masked)
        assertNull(n.text)
    }

    @Test
    fun `web views are masked leaves with text or input masking`() {
        val web = V("WebView", isWebView = true, children = listOf(V("Inner", NodeKind.TEXT, text = "hi")))
        val masked = build(V("Root", children = listOf(web)))!!.find("WebView")!!
        assertEquals(true, masked.masked)
        assertNull(masked.children)

        val inputsOnly = build(V("Root", children = listOf(web)), policy = open.copy(maskInputs = true))!!
        assertEquals(true, inputsOnly.find("WebView")!!.masked)

        val off = build(V("Root", children = listOf(web)), policy = open)!!.find("WebView")!!
        assertNull(off.masked)
        assertNull("still a leaf", off.children)

        val unmasked = build(V("Root", isUnmasked = true, children = listOf(web)))!!.find("WebView")!!
        assertNull(unmasked.masked)
    }

    // ── masking and visibility (unchanged rules) ──────────────────────────

    @Test
    fun `text inputs and images follow the masking rules`() {
        val root = V("Root", children = listOf(
            V("Label", NodeKind.TEXT, text = "Hi you", textColor = "#000000"),
            V("Email", NodeKind.INPUT, text = "a@b.c"),
            V("Pw", NodeKind.INPUT, text = "hunter2", isPassword = true),
            V("Avatar", NodeKind.IMAGE),
        ))
        val t = build(root, policy = defaults.copy(maskImages = true))!!
        assertEquals("•• •••", t.find("Label")!!.text)
        assertEquals(true, t.find("Label")!!.redacted)
        assertEquals("#000000", t.find("Label")!!.textColor)
        assertEquals("[MASKED]", t.find("Email")!!.text)
        assertEquals("edit", t.find("Email")!!.inputType)
        assertEquals(true, t.find("Email")!!.interactive)
        assertEquals("[MASKED]", t.find("Pw")!!.text)
        assertEquals(true, t.find("Pw")!!.isPassword)
        assertEquals(true, t.find("Avatar")!!.isImage)
        assertEquals(true, t.find("Avatar")!!.masked)

        val clear = build(root, policy = open)!!
        assertEquals("Hi you", clear.find("Label")!!.text)
        assertNull(clear.find("Label")!!.redacted)
        assertEquals("a@b.c", clear.find("Email")!!.text)
        assertEquals("[MASKED]", clear.find("Pw")!!.text)
        assertNull(clear.find("Avatar")!!.masked)
    }

    @Test
    fun `text is cut to 200 characters`() {
        val t = build(V("Root", children = listOf(V("Long", NodeKind.TEXT, text = "x".repeat(500)))), policy = open)!!
        assertEquals(200, t.find("Long")!!.text!!.length)
    }

    @Test
    fun `gone and empty views are skipped, invisible ones kept as hidden`() {
        val root = V("Root", children = listOf(
            V("Gone", isGone = true, isVisible = false),
            V("Empty", frame = MaskRect(10, 10, 10, 50)),
            V("Invisible", isVisible = false, backgroundColor = "#FF0000",
                children = listOf(V("Child", NodeKind.TEXT, text = "a"))),
        ))
        val t = build(root)!!
        assertNull(t.find("Gone"))
        assertNull(t.find("Empty"))
        val inv = t.find("Invisible")!!
        assertEquals(true, inv.hidden)
        assertNull(inv.bgColor)
        assertEquals("Child", inv.children!!.single().type)
    }

    // ── caps ──────────────────────────────────────────────────────────────

    private fun flat(n: Int) = V("Root", children = List(n) { V("Leaf$it") })

    private fun chain(depth: Int): V {
        var v = V("N$depth")
        for (d in depth - 1 downTo 0) v = V("N$d", children = listOf(v))
        return v
    }

    @Test
    fun `iOS caps`() {
        assertEquals(2000, SnapshotBuilder.MAX_NODES)
        assertEquals(64, SnapshotBuilder.MAX_DEPTH)
    }

    @Test
    fun `node cap stops adding nodes without a marker`() {
        val t = build(flat(3000))!!
        assertEquals(2000, t.nodeCount)
        assertEquals("Leaf1998", t.children!!.last().type)
        assertEquals(5, build(flat(10), maxNodes = 5)!!.nodeCount)
        assertEquals("a cap below 1 still yields the root", 1, build(flat(10), maxNodes = 0)!!.nodeCount)
    }

    @Test
    fun `depth cap keeps the node at the cap but not its children`() {
        val t = build(chain(100))!!
        assertEquals(65, t.nodeCount)                 // depths 0…64
        var n: SnapshotNode = t
        while (n.children != null) n = n.children!!.single()
        assertEquals("N64", n.type)
        assertEquals(3, build(chain(10), maxDepth = 2)!!.nodeCount)
        assertEquals(11, build(chain(10))!!.nodeCount)
    }

    // ── scheduler and dedupe ──────────────────────────────────────────────

    private fun tree(text: String = "a") =
        SnapshotNode("Root", SnapshotBounds(0, 0, 360, 800), children = listOf(
            SnapshotNode("TextView", SnapshotBounds(0, 0, 100, 20), text = text)))

    @Test
    fun `interval is clamped to 1-60 s`() {
        assertEquals(1_000L, SnapshotScheduler(10).intervalMs)
        assertEquals(60_000L, SnapshotScheduler(600_000).intervalMs)
        assertEquals(2_500L, SnapshotScheduler(2_500).intervalMs)
    }

    @Test
    fun `walks once per interval and first walk is immediate`() {
        val s = SnapshotScheduler(1_000)
        assertTrue(s.shouldWalk(0, "a"))
        assertFalse(s.shouldWalk(250, "a"))
        assertFalse(s.shouldWalk(999, "a"))
        assertTrue(s.shouldWalk(1_000, "a"))
    }

    @Test
    fun `unchanged tree is dropped, changes are kept`() {
        val s = SnapshotScheduler(1_000)
        assertTrue(s.accept(tree(), "Main", 360, 800, 0))
        assertFalse(s.accept(tree(), "Main", 360, 800, 1_000))
        assertTrue("text changed", s.accept(tree("b"), "Main", 360, 800, 2_000))
        assertTrue("screen changed", s.accept(tree("b"), "Other", 360, 800, 3_000))
        assertTrue("viewport changed (rotation)", s.accept(tree("b"), "Other", 800, 360, 4_000))
        assertFalse(s.accept(tree("b"), "Other", 800, 360, 5_000))
    }

    @Test
    fun `navigation walks as soon as the interval since the last kept snapshot allows`() {
        val s = SnapshotScheduler(5_000)
        assertTrue(s.shouldWalk(0, "a"))
        assertTrue(s.accept(tree(), "Main", 360, 800, 0))
        s.markNavigation()
        assertFalse(s.shouldWalk(1_000, "a"))
        assertTrue(s.navigationPending)
        assertTrue(s.shouldWalk(5_000, "a"))
        assertFalse(s.navigationPending)
    }

    @Test
    fun `navigation after a dropped walk is not held back by that walk`() {
        val s = SnapshotScheduler(1_000)
        assertTrue(s.shouldWalk(0, "a")); assertTrue(s.accept(tree(), "Main", 360, 800, 0))
        assertTrue(s.shouldWalk(5_000, "a")); assertFalse(s.accept(tree(), "Main", 360, 800, 5_000))
        s.markNavigation()
        assertTrue("1 s since the last kept snapshot", s.shouldWalk(5_100, "a"))
    }

    @Test
    fun `a new session walks immediately and forgets the last tree`() {
        val s = SnapshotScheduler(10_000)
        assertTrue(s.shouldWalk(0, "a")); assertTrue(s.accept(tree(), "Main", 360, 800, 0))
        assertFalse(s.shouldWalk(100, "a"))
        assertTrue(s.shouldWalk(200, "b"))
        assertTrue("same tree, new session: kept", s.accept(tree(), "Main", 360, 800, 200))
    }

    @Test
    fun `ordinals count kept snapshots only`() {
        val s = SnapshotScheduler(1_000)
        var ordinal = 0
        val ordinals = mutableListOf<Int>()
        val texts = listOf("a", "a", "a", "b", "b", "c")
        texts.forEachIndexed { i, t ->
            val now = i * 1_000L
            if (s.shouldWalk(now, "s") && s.accept(tree(t), "Main", 360, 800, now)) ordinals += ordinal++
        }
        assertEquals(listOf(0, 1, 2), ordinals)
    }
}
