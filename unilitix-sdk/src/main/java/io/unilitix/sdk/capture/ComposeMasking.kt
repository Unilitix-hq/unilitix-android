package io.unilitix.sdk.capture

// Jetpack Compose in session replay: the semantics tree of each
// AndroidComposeView, copied into plain values on the main thread
// (ComposeSemanticsReader), then masked by the same rules as views
// (ReplayMasking, SnapshotBuilder). Pure Kotlin, no Compose or Android types:
// unit-tested on the JVM with hand-built trees.

/**
 * One node of the unmerged Compose semantics tree. Rects are pixels relative
 * to the top-left corner of the AndroidComposeView that hosts the tree.
 */
internal data class ComposeNodeInfo(
    /** Position and size of the node, not clipped. */
    val frame: MaskRect,
    /** The part left after ancestor clipping (scroll containers); null when nothing is. */
    val visible: MaskRect?,
    /** `SemanticsProperties.Text` (a `Text`, or a text field's label), joined with spaces. */
    val text: String? = null,
    /** `SemanticsProperties.EditableText`: a text field's value. */
    val editableText: String? = null,
    /** Has `EditableText` or the `SetText` action: a text field. */
    val isEditable: Boolean = false,
    /** `SemanticsProperties.Password`. */
    val isPassword: Boolean = false,
    /** `SemanticsProperties.ContentDescription`, joined with spaces. */
    val contentDescription: String? = null,
    /** `SemanticsProperties.Role` by name ("Button", "Image", …), or null. */
    val role: String? = null,
    /** Has the `OnClick` action. */
    val isClickable: Boolean = false,
    /** `Modifier.unilitixUnmask()`: this node and its subtree are exempt from text/image masking. */
    val isUnmasked: Boolean = false,
    /** `Modifier.unilitixPrivate()`: always masked, as one rect / one `masked` node. */
    val isPrivate: Boolean = false,
    val children: List<ComposeNodeInfo> = emptyList(),
) {
    /**
     * Text fields (and anything marked password) are inputs; `Role.Image`
     * (`Image`, `Icon`) is an image; any other node carrying text or a content
     * description is text. A content description without an image role is
     * masked as text because it often names text drawn on a Canvas (a
     * balance, a chart value) that the semantics tree does not otherwise show.
     */
    val kind: NodeKind
        get() = when {
            isEditable || isPassword -> NodeKind.INPUT
            role == ROLE_IMAGE -> NodeKind.IMAGE
            text != null || contentDescription != null -> NodeKind.TEXT
            else -> NodeKind.OTHER
        }

    /** The text a snapshot node shows, before masking. */
    val displayText: String?
        get() = when (kind) {
            NodeKind.INPUT -> editableText ?: ""
            NodeKind.TEXT -> text ?: contentDescription
            else -> null
        }

    /** Snapshot `type`: Compose has no class per node, so a name from its semantics. */
    val typeName: String
        get() = when {
            kind == NodeKind.INPUT -> "TextField"
            role != null -> role
            kind == NodeKind.TEXT -> "Text"
            else -> "ComposeNode"
        }

    companion object {
        const val ROLE_IMAGE = "Image"
    }
}

/**
 * The result of reading one AndroidComposeView's semantics.
 *
 * [failed]: the tree could not be read (Compose missing or of an unexpected
 * shape, or the read threw); [root] is null. [truncated]: the node or depth
 * cap was reached, so part of the tree was not seen.
 */
internal data class ComposeTree(
    val root: ComposeNodeInfo?,
    val truncated: Boolean = false,
    val failed: Boolean = false,
) {
    /**
     * Screenshots fail closed: a tree that was not seen in full may hide text,
     * so the whole host view is painted over (see
     * [ReplayMasking.maskUnreadableInScreenshot]). Snapshots only ever contain
     * what was read, so a truncated tree is simply cut short there.
     */
    val unreadableForScreenshot: Boolean get() = failed || truncated || root == null

    companion object {
        /** Same caps as view snapshots: one Compose view cannot stall the main thread. */
        const val MAX_NODES = SnapshotBuilder.MAX_NODES
        const val MAX_DEPTH = SnapshotBuilder.MAX_DEPTH

        val FAILED = ComposeTree(root = null, failed = true)
    }
}

internal fun MaskRect.offset(dx: Int, dy: Int): MaskRect = MaskRect(left + dx, top + dy, right + dx, bottom + dy)

/** The overlap of two rects, or null when they do not overlap. */
internal fun MaskRect.intersect(other: MaskRect): MaskRect? {
    val l = maxOf(left, other.left)
    val t = maxOf(top, other.top)
    val r = minOf(right, other.right)
    val b = minOf(bottom, other.bottom)
    return if (r > l && b > t) MaskRect(l, t, r, b) else null
}

/**
 * A Compose node for [ReplayMasking.screenshotRects]. [dx]/[dy] move the
 * node from host-view coordinates to the screenshot's (root-view pixels);
 * [clip] is the host view's visible part there, so nothing is painted
 * outside it.
 */
internal class ComposeMaskNode(
    private val node: ComposeNodeInfo,
    private val dx: Int,
    private val dy: Int,
    private val clip: MaskRect,
) : MaskableNode {
    override val kind: NodeKind get() = node.kind
    override val isVisible: Boolean get() = true
    override val isPrivate: Boolean get() = node.isPrivate
    override val isMaskedById: Boolean get() = false
    override val isUnmasked: Boolean get() = node.isUnmasked
    override val isPassword: Boolean get() = node.isPassword
    override val rect: MaskRect? get() = node.visible?.offset(dx, dy)?.intersect(clip)
    override val children: List<MaskableNode> get() = node.children.map { ComposeMaskNode(it, dx, dy, clip) }
}

/**
 * A Compose node for [SnapshotBuilder]. [dx]/[dy] move the node from
 * host-view coordinates to the snapshot's (pixels from the window origin).
 * Compose exposes no colours through semantics, so none are sent.
 */
internal class ComposeSnapshotNode(
    private val node: ComposeNodeInfo,
    private val dx: Int,
    private val dy: Int,
) : SnapshotViewNode {
    override val typeName: String get() = node.typeName
    override val kind: NodeKind get() = node.kind
    override val isGone: Boolean get() = false
    override val isWebView: Boolean get() = false
    override val isVisible: Boolean get() = true
    override val isPrivate: Boolean get() = node.isPrivate
    override val isMaskedById: Boolean get() = false
    override val isUnmasked: Boolean get() = node.isUnmasked
    override val isPassword: Boolean get() = node.isPassword
    override val frame: MaskRect get() = node.frame.offset(dx, dy)
    override val text: String? get() = node.displayText
    override val textColor: String? get() = null
    override val backgroundColor: String? get() = null
    override val isInteractive: Boolean get() = node.isClickable
    override val children: List<SnapshotViewNode> get() = node.children.map { ComposeSnapshotNode(it, dx, dy) }
}
