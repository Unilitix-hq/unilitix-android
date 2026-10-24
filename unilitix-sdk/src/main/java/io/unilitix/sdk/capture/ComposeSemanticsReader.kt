package io.unilitix.sdk.capture

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.unilitix.sdk.compose.UnilitixSemantics
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The only SDK code typed against Jetpack Compose (`compileOnly`
 * `androidx.compose.ui:ui`): it is loaded only through [ComposeHost] once
 * Compose was found at runtime.
 *
 * Public, stable Compose UI API only, no reflection:
 * `AndroidComposeView` implements `RootForTest`, whose `semanticsOwner`
 * gives the semantics tree (the one accessibility services and Compose UI
 * tests read). The unmerged tree is walked, so every `Text` is its own node
 * with its own bounds. All of it exists since Compose UI 1.0.
 *
 * Main thread only (Compose's own rule for reading semantics).
 */
@SuppressLint("VisibleForTests") // RootForTest is a public interface, documented for tests
internal object ComposeSemanticsReader {

    /** Loads a Compose class: throws NoClassDefFoundError when the app has no Compose. */
    fun probe() {
        RootForTest::class.java.name
    }

    fun isRoot(view: View): Boolean = view is RootForTest

    fun read(view: View, maxNodes: Int, maxDepth: Int): ComposeTree {
        val owner = (view as RootForTest).semanticsOwner
        val root = owner.unmergedRootSemanticsNode
        // Every position is taken relative to the root node (the host view's
        // top-left), whatever Compose's own notion of "window" is.
        val origin = root.positionInWindow
        val walk = Walk(origin.x, origin.y, maxNodes, maxDepth)
        val info = walk.visit(root, 0)
        return ComposeTree(root = info, truncated = walk.truncated, failed = false)
    }

    private class Walk(val ox: Float, val oy: Float, maxNodes: Int, val maxDepth: Int) {
        var budget = maxOf(1, maxNodes)
        var truncated = false

        fun visit(node: SemanticsNode, depth: Int): ComposeNodeInfo {
            budget -= 1
            val config = node.config
            val pos = node.positionInWindow
            val size = node.size
            val left = (pos.x - ox).roundToInt()
            val top = (pos.y - oy).roundToInt()
            val frame = MaskRect(left, top, left + size.width, top + size.height)
            // boundsInWindow is clipped by ancestors (scrolling lists); rounded
            // outwards so a masked rect never leaves a pixel row of text.
            val b = node.boundsInWindow
            val visible = if (b.width > 0f && b.height > 0f) {
                MaskRect(
                    floor(b.left - ox).toInt(), floor(b.top - oy).toInt(),
                    ceil(b.right - ox).toInt(), ceil(b.bottom - oy).toInt(),
                )
            } else null

            val children = ArrayList<ComposeNodeInfo>()
            val kids = node.children
            if (kids.isNotEmpty()) {
                if (depth >= maxDepth) {
                    truncated = true
                } else {
                    for (kid in kids) {
                        if (budget <= 0) {
                            truncated = true
                            break
                        }
                        // Composed but not placed (a pager's off-screen page): not drawn.
                        if (!kid.layoutInfo.isPlaced) continue
                        children.add(visit(kid, depth + 1))
                    }
                }
            }

            return ComposeNodeInfo(
                frame = frame,
                visible = visible,
                text = config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ") { it.text },
                editableText = config.getOrNull(SemanticsProperties.EditableText)?.text,
                isEditable = SemanticsProperties.EditableText in config || SemanticsActions.SetText in config,
                isPassword = SemanticsProperties.Password in config,
                contentDescription = config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ")?.takeIf { it.isNotEmpty() },
                role = config.getOrNull(SemanticsProperties.Role)?.let(::roleName),
                isClickable = SemanticsActions.OnClick in config,
                isUnmasked = config.getOrNull(UnilitixSemantics.Unmasked) == true,
                isPrivate = config.getOrNull(UnilitixSemantics.Private) == true,
                children = children,
            )
        }
    }

    private fun roleName(role: Role): String? = when (role) {
        Role.Button -> "Button"
        Role.Checkbox -> "Checkbox"
        Role.Switch -> "Switch"
        Role.RadioButton -> "RadioButton"
        Role.Tab -> "Tab"
        Role.Image -> ComposeNodeInfo.ROLE_IMAGE
        else -> null
    }
}
