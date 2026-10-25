package io.unilitix.sdk.capture

/** A rectangle in root-view pixels. Plain data so masking is testable on the JVM. */
internal data class MaskRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal enum class NodeKind {
    /** EditText and subclasses. */
    INPUT,
    /** Any other TextView (labels, Buttons, CheckBoxes…). */
    TEXT,
    /** ImageView with a drawable, or a leaf view whose background is a bitmap. */
    IMAGE,
    OTHER,
}

/**
 * What [ReplayMasking] needs to know about one view. [ScreenshotCapture]
 * adapts `android.view.View` to this on the main thread; tests build trees
 * of it directly.
 */
internal interface MaskableNode {
    val kind: NodeKind
    /** Drawn: `visibility == VISIBLE`. Hidden subtrees are skipped. */
    val isVisible: Boolean
    /** Class annotated `@UnilitixPrivate`: the whole subtree is one masked rect. */
    val isPrivate: Boolean
    /** Listed in `maskedViewIds`: always masked, even inside an unmasked subtree. */
    val isMaskedById: Boolean
    /** Marked with `Unilitix.unmask(view)`: this view and its subtree are exempt from text/image masking. */
    val isUnmasked: Boolean
    /** Password input (text, web or number password). */
    val isPassword: Boolean
    /** The part of the view visible on screen, relative to the root; null when none of it is. */
    val rect: MaskRect?
    val children: List<MaskableNode>
    /**
     * The view draws content the walk cannot see into: a Jetpack Compose view
     * whose semantics could not be read in full. Masked whole (fail closed),
     * see [ReplayMasking.maskUnreadableInScreenshot].
     */
    val isUnreadable: Boolean get() = false
}

internal data class MaskPolicy(
    val maskInputs: Boolean,
    val maskInputsInScreenshots: Boolean,
    val maskAllText: Boolean,
    val maskImages: Boolean,
)

/** Text for a snapshot node; [redacted] marks text whose characters were replaced. */
internal data class MaskedText(val text: String, val redacted: Boolean)

/**
 * Privacy rules for session replay, shared by screenshots (rects painted
 * over the bitmap) and snapshots (text replaced in the view tree). Mirrors
 * the iOS `ReplayPrivacy` and Flutter `PrivacyRegistry` rules:
 *
 * - `@UnilitixPrivate` classes and `maskedViewIds`: always masked.
 * - Inputs: masked when `maskInputs` is on (passwords always), in
 *   screenshots only while `maskInputsInScreenshots` is on. `unmask` never
 *   exempts an input from this.
 * - `maskAllText` (default on): every TextView, Button and input.
 * - `maskImages` (default off): every ImageView and bitmap background.
 * - WebView: its page cannot be walked, so in screenshots it is masked whole
 *   (like an unreadable Compose view, below); in snapshots it is one masked
 *   node with `maskAllText` or `maskInputs`.
 * - A window with `FLAG_SECURE` is never captured (screenshots or snapshots).
 * - Jetpack Compose: each node of the semantics tree is classified like a
 *   view (text field = input, `Role.Image` = image, text or content
 *   description = text). A Compose view whose semantics cannot be read in
 *   full is masked whole in screenshots and is one masked node in snapshots.
 * - `Unilitix.unmask(view)` exempts that view and its subtree from the two
 *   options above.
 */
internal object ReplayMasking {

    const val MASKED = "[MASKED]"

    /** True for a window the app marked FLAG_SECURE: it is never captured. */
    fun isSecureWindow(windowFlags: Int): Boolean =
        (windowFlags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
    private const val REDACTED_CHAR = '•'

    /** Rects to paint over in a screenshot, in traversal order. */
    fun screenshotRects(root: MaskableNode, policy: MaskPolicy): List<MaskRect> {
        val out = mutableListOf<MaskRect>()
        collect(root, policy, unmasked = false, out = out)
        return out
    }

    private fun collect(node: MaskableNode, policy: MaskPolicy, unmasked: Boolean, out: MutableList<MaskRect>) {
        if (!node.isVisible) return
        if (node.isPrivate) {
            node.rect?.let { out.add(it) }
            return // the rect covers every child
        }
        val exempt = unmasked || node.isUnmasked
        if (node.isUnreadable && maskUnreadableInScreenshot(policy, exempt)) {
            node.rect?.let { out.add(it) }
            return // the rect covers every child
        }
        if (shouldMaskInScreenshot(node, policy, exempt)) node.rect?.let { out.add(it) }
        for (child in node.children) collect(child, policy, exempt, out)
    }

    /**
     * True when a view whose content cannot be read ([MaskableNode.isUnreadable])
     * is painted over whole: whenever anything it may contain would be masked.
     * Inputs (passwords at least) are masked in screenshots whenever
     * `maskInputsInScreenshots` is on, and `unmask` never exempts inputs, so
     * that alone masks it; text and images follow their options.
     */
    fun maskUnreadableInScreenshot(policy: MaskPolicy, unmasked: Boolean): Boolean =
        policy.maskInputsInScreenshots || ((policy.maskAllText || policy.maskImages) && !unmasked)

    /** [unmasked] is true when the node or an ancestor was passed to `Unilitix.unmask`. */
    fun shouldMaskInScreenshot(node: MaskableNode, policy: MaskPolicy, unmasked: Boolean): Boolean {
        if (node.isPrivate || node.isMaskedById) return true
        return when (node.kind) {
            NodeKind.INPUT ->
                (policy.maskInputsInScreenshots && (node.isPassword || policy.maskInputs)) ||
                    (policy.maskAllText && !unmasked)
            NodeKind.TEXT  -> policy.maskAllText && !unmasked
            NodeKind.IMAGE -> policy.maskImages && !unmasked
            NodeKind.OTHER -> false
        }
    }

    /**
     * Text for a snapshot node. Inputs under `maskInputs` (and passwords)
     * become `[MASKED]` as before; with `maskAllText` every other text keeps
     * its word shapes and length but no characters (see [redact]).
     */
    fun snapshotText(raw: String, isInput: Boolean, isPassword: Boolean, unmasked: Boolean, policy: MaskPolicy): MaskedText {
        if (isInput && (isPassword || policy.maskInputs)) return MaskedText(MASKED, redacted = false)
        if (policy.maskAllText && !unmasked) return MaskedText(redact(raw), redacted = true)
        return MaskedText(raw, redacted = false)
    }

    /** True when an image node's content must be dropped from a snapshot. */
    fun maskSnapshotImage(unmasked: Boolean, policy: MaskPolicy): Boolean = policy.maskImages && !unmasked

    /**
     * True when a web view's snapshot node is marked masked: with `maskAllText`
     * or `maskInputs`, unless inside an unmasked subtree (as iOS). Its text and
     * inputs cannot be found, so its subtree is never walked either way.
     */
    fun maskSnapshotWebView(unmasked: Boolean, policy: MaskPolicy): Boolean =
        (policy.maskAllText || policy.maskInputs) && !unmasked

    /**
     * Replaces every non-whitespace character with `•`, keeping whitespace,
     * so the replay keeps layout (word lengths, line breaks) but never the
     * characters. Same as the Flutter SDK's `redactText`.
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) sb.appendCodePoint(cp) else sb.append(REDACTED_CHAR)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
