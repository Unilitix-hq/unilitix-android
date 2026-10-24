package io.unilitix.sdk.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

// Session replay privacy markers for Jetpack Compose, the Compose versions of
// Unilitix.unmask(view) and @UnilitixPrivate. Plain Modifier extensions (no
// @Composable, so the SDK needs no Compose compiler) on Compose UI's public
// semantics API. The SDK declares Compose UI `compileOnly`: these are usable
// only in apps that have Compose, which is the only place they make sense.

/** The semantics keys the SDK's Compose walk reads. Not for accessibility services. */
internal object UnilitixSemantics {
    val Unmasked = SemanticsPropertyKey<Boolean>("UnilitixUnmasked")
    val Private = SemanticsPropertyKey<Boolean>("UnilitixPrivate")
}

/**
 * Shows this composable and everything inside it in session replay even when
 * `maskAllText` or `maskImages` is on. Use it for content with no personal
 * data that is needed to understand the screen: headings, button labels,
 * navigation, static copy. Pass `false` to mask it again.
 *
 * Like `Unilitix.unmask(view)`, it never overrides [unilitixPrivate] or
 * input masking: a text field inside is still masked under `maskInputs`, and
 * passwords always.
 *
 * ```kotlin
 * Text("Order summary", Modifier.unilitixUnmask())
 * ```
 */
fun Modifier.unilitixUnmask(unmasked: Boolean = true): Modifier =
    semantics(mergeDescendants = false) { this[UnilitixSemantics.Unmasked] = unmasked }

/**
 * Always masks this composable and everything inside it in session replay,
 * whatever else is set: one painted-over rect in screenshots, one `masked`
 * node (no text, no children) in snapshots. The Compose version of
 * `@UnilitixPrivate`.
 *
 * ```kotlin
 * Column(Modifier.unilitixPrivate()) { CardNumber(); Cvv() }
 * ```
 */
fun Modifier.unilitixPrivate(): Modifier =
    semantics(mergeDescendants = false) { this[UnilitixSemantics.Private] = true }
