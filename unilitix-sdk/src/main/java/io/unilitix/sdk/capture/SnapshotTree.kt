package io.unilitix.sdk.capture

import kotlin.math.roundToInt

// View-tree snapshots: the masking tree walk, the dp conversion and the
// capture schedule. Pure Kotlin (no Android types), unit-tested on the JVM;
// the View side is in SnapshotCapture.kt. Mirrors UnilitixCore (iOS) 0.4.0
// `Snapshot.swift`.

/**
 * A view's frame in dp, relative to the window's top-left corner — the unit
 * and origin of `viewportWidth`/`viewportHeight`. `bounds` in the hierarchy
 * JSON.
 */
internal data class SnapshotBounds(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * One node of a snapshot's `hierarchy`, immutable so it can be built on the
 * main thread and turned into JSON on a background thread. Null fields are
 * left out of the JSON. Field names are the ones the dashboard's
 * `ReplayFrame` renders and iOS sends.
 */
internal data class SnapshotNode(
    /** The view's simple class name. */
    val type: String,
    val bounds: SnapshotBounds,
    /** Masked per [ReplayMasking.snapshotText]. */
    val text: String? = null,
    /** The text's characters were replaced (`maskAllText`). */
    val redacted: Boolean? = null,
    /** "#RRGGBB". */
    val textColor: String? = null,
    /** "#RRGGBB". */
    val bgColor: String? = null,
    /** Inputs only. */
    val isPassword: Boolean? = null,
    /** Inputs only: "edit". */
    val inputType: String? = null,
    val interactive: Boolean? = null,
    val isImage: Boolean? = null,
    /**
     * The view's content is withheld: a private view (`@UnilitixPrivate` or
     * `maskedViewIds`), an image under `maskImages`, or a web view.
     */
    val masked: Boolean? = null,
    /** `INVISIBLE`: occupies space but is not drawn; children are still walked. */
    val hidden: Boolean? = null,
    val children: List<SnapshotNode>? = null,
) {
    /** Nodes in this tree, this one included. */
    val nodeCount: Int get() = 1 + (children?.sumOf { it.nodeCount } ?: 0)
}

/**
 * Pixel → dp ("logical px" in the SDK contract) conversion, rounded to the
 * nearest whole dp (iOS rounds points the same way). Used for snapshot
 * bounds and viewports, screenshot viewports, tap positions and screen size.
 */
internal object Dp {
    private fun safe(density: Float) = if (density > 0f && density.isFinite()) density else 1f

    fun of(px: Int, density: Float): Int = (px / safe(density)).roundToInt()

    /**
     * A touch position: [rawPx] is `MotionEvent.rawX`/`rawY` (screen pixels),
     * [originPx] the window's left/top on screen. Same origin and rounding as
     * snapshot bounds, so taps line up with snapshot nodes.
     */
    fun point(rawPx: Float, originPx: Int, density: Float): Int =
        ((rawPx - originPx) / safe(density)).roundToInt()

    /** [frame] is in pixels relative to the window origin. */
    fun bounds(frame: MaskRect, density: Float): SnapshotBounds = SnapshotBounds(
        x = of(frame.left, density),
        y = of(frame.top, density),
        w = of(frame.right - frame.left, density),
        h = of(frame.bottom - frame.top, density),
    )
}

/**
 * What the snapshot walk needs to know about one view, on top of the
 * [MaskableNode] masking facts. `SnapshotCapture` adapts `android.view.View`
 * to this on the main thread; tests build trees of plain values.
 *
 * [MaskableNode.isVisible] is `visibility == VISIBLE`; [isGone] is
 * `visibility == GONE` (an `INVISIBLE` view is neither and is kept as a
 * `hidden` node).
 */
internal interface SnapshotViewNode : MaskableNode {
    /** The view's simple class name. */
    val typeName: String
    /** `visibility == GONE`: skipped with its subtree. */
    val isGone: Boolean
    /** `android.webkit.WebView` (or a subclass): its text and inputs cannot be found. */
    val isWebView: Boolean
    /** The whole frame, in pixels relative to the window origin (not clipped). */
    val frame: MaskRect
    /** The view's own text; read only for [NodeKind.INPUT] and [NodeKind.TEXT]. */
    val text: String?
    val textColor: String?
    val backgroundColor: String?
    val isInteractive: Boolean
    override val children: List<SnapshotViewNode>
    override val rect: MaskRect? get() = frame
}

/**
 * Serializes a view tree into a [SnapshotNode] tree, applying the replay
 * masking rules on the way so no raw text that a screenshot would mask is
 * ever put in a node. Same walk as iOS `SnapshotBuilder`:
 *
 * - `GONE` and zero-size views are skipped with their subtrees.
 * - `@UnilitixPrivate` views and views in `maskedViewIds` become a node with
 *   `type`, `bounds` and `masked: true` only: no text, no children.
 * - Web views are leaves, `masked` when `maskAllText` or `maskInputs` is on
 *   (unless inside an unmasked subtree).
 * - At most [MAX_NODES] nodes and [MAX_DEPTH] levels below the root, so a
 *   huge or deeply nested screen cannot stall the main thread. Past the cap
 *   nodes are simply left out; nothing marks the truncation (as on iOS).
 */
internal object SnapshotBuilder {
    const val MAX_NODES = 2000
    const val MAX_DEPTH = 64
    /** Longest text kept in a node, before masking. */
    const val MAX_TEXT_LENGTH = 200

    fun build(
        root: SnapshotViewNode,
        density: Float,
        policy: MaskPolicy,
        maxNodes: Int = MAX_NODES,
        maxDepth: Int = MAX_DEPTH,
    ): SnapshotNode? {
        val budget = intArrayOf(maxOf(1, maxNodes))
        return visit(root, 0, false, density, policy, maxDepth, budget)
    }

    private fun visit(
        view: SnapshotViewNode,
        depth: Int,
        inherited: Boolean,
        density: Float,
        policy: MaskPolicy,
        maxDepth: Int,
        budget: IntArray,
    ): SnapshotNode? {
        if (budget[0] <= 0 || view.isGone) return null
        val f = view.frame
        if (f.right - f.left <= 0 || f.bottom - f.top <= 0) return null
        budget[0] -= 1

        val type = view.typeName
        val bounds = Dp.bounds(f, density)
        if (view.isPrivate || view.isMaskedById) {
            return SnapshotNode(type = type, bounds = bounds, masked = true)
        }
        val unmasked = inherited || view.isUnmasked

        var text: String? = null
        var redacted: Boolean? = null
        var textColor: String? = null
        var bgColor: String? = null
        var isPassword: Boolean? = null
        var inputType: String? = null
        var interactive: Boolean? = null
        var isImage: Boolean? = null
        var masked: Boolean? = null
        var hidden: Boolean? = null
        var descend = true

        if (!view.isVisible) {
            // INVISIBLE: keep the bounds for layout, mark hidden, still walk the children.
            hidden = true
        } else {
            when {
                view.isWebView -> {
                    if (ReplayMasking.maskSnapshotWebView(unmasked, policy)) masked = true
                    descend = false
                }
                view.kind == NodeKind.INPUT -> {
                    val m = ReplayMasking.snapshotText(
                        (view.text ?: "").take(MAX_TEXT_LENGTH), isInput = true,
                        isPassword = view.isPassword, unmasked = unmasked, policy = policy,
                    )
                    text = m.text
                    if (m.redacted) redacted = true
                    isPassword = view.isPassword
                    inputType = "edit"
                    interactive = true
                }
                view.kind == NodeKind.TEXT -> {
                    val m = ReplayMasking.snapshotText(
                        (view.text ?: "").take(MAX_TEXT_LENGTH), isInput = false,
                        isPassword = false, unmasked = unmasked, policy = policy,
                    )
                    text = m.text
                    if (m.redacted) redacted = true
                    textColor = view.textColor
                }
                view.kind == NodeKind.IMAGE -> {
                    isImage = true
                    if (ReplayMasking.maskSnapshotImage(unmasked, policy)) masked = true
                }
            }
            bgColor = view.backgroundColor
            if (view.isInteractive) interactive = true
        }

        var children: List<SnapshotNode>? = null
        if (descend && depth < maxDepth) {
            val kids = view.children
            if (kids.isNotEmpty()) {
                val out = ArrayList<SnapshotNode>(minOf(kids.size, budget[0]))
                for (child in kids) {
                    if (budget[0] <= 0) break
                    visit(child, depth + 1, unmasked, density, policy, maxDepth, budget)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) children = out
            }
        }

        return SnapshotNode(
            type = type, bounds = bounds, text = text, redacted = redacted, textColor = textColor,
            bgColor = bgColor, isPassword = isPassword, inputType = inputType, interactive = interactive,
            isImage = isImage, masked = masked, hidden = hidden, children = children,
        )
    }
}

/**
 * Decides when the view tree is walked and whether the result is kept. Same
 * rules as iOS `SnapshotScheduler`:
 *
 * - The first walk of every session, and the first after a navigation (an
 *   activity resuming, which includes a return to the foreground), happen at
 *   the next tick, as long as [intervalMs] passed since the last snapshot taken.
 * - Otherwise the tree is walked once per [intervalMs] (`snapshotIntervalMs`,
 *   clamped to 1–60 s).
 * - A walk whose result equals the last snapshot taken in the session (same
 *   screen, viewport and tree) is dropped: a static screen costs one snapshot,
 *   not one per interval. Only kept snapshots get an ordinal.
 *
 * Times are any monotonic clock in milliseconds (`SystemClock.uptimeMillis`).
 * Not thread-safe; `SnapshotCapture` uses it on the main thread only.
 */
internal class SnapshotScheduler(intervalMs: Long) {
    val intervalMs: Long = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    var navigationPending: Boolean = true
        private set

    private var lastWalk: Long? = null
    private var lastTaken: Long? = null
    private var last: Taken? = null
    private var session: String? = null

    private data class Taken(val screen: String, val viewportWidth: Int, val viewportHeight: Int, val tree: SnapshotNode)

    /** A new screen: walk at the next tick the interval allows. */
    fun markNavigation() {
        navigationPending = true
    }

    /** True when the tree should be walked now. */
    fun shouldWalk(now: Long, sessionId: String): Boolean {
        if (sessionId != session) {
            session = sessionId
            navigationPending = true
            lastWalk = null
            lastTaken = null
            last = null
        }
        val sinceTaken = lastTaken?.let { now - it } ?: Long.MAX_VALUE
        val sinceWalk = lastWalk?.let { now - it } ?: Long.MAX_VALUE
        val due = (navigationPending && sinceTaken >= intervalMs) || sinceWalk >= intervalMs
        if (!due) return false
        navigationPending = false
        lastWalk = now
        return true
    }

    /**
     * After a walk: true when the result differs from the last snapshot taken
     * in this session, which it then replaces.
     */
    fun accept(tree: SnapshotNode, screen: String, viewportWidth: Int, viewportHeight: Int, now: Long): Boolean {
        val taken = Taken(screen, viewportWidth, viewportHeight, tree)
        if (taken == last) return false
        last = taken
        lastTaken = now
        return true
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_INTERVAL_MS = 60_000L
    }
}
