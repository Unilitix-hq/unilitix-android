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
 * - `Unilitix.unmask(view)` exempts that view and its subtree from the two
 *   options above.
 */
internal object ReplayMasking {

    const val MASKED = "[MASKED]"
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
        if (shouldMaskInScreenshot(node, policy, exempt)) node.rect?.let { out.add(it) }
        for (child in node.children) collect(child, policy, exempt, out)
    }

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
