package io.unilitix.sdk.capture

import android.view.View
import io.unilitix.sdk.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds Jetpack Compose host views (`AndroidComposeView`) during the replay
 * walks and reads their semantics, failing closed.
 *
 * The SDK has no runtime dependency on Compose (`compileOnly`): everything
 * typed against Compose is in [ComposeSemanticsReader], which is only
 * touched after [available] found Compose UI on the classpath. An app
 * without Compose never loads it.
 *
 * Main thread only.
 */
internal object ComposeHost {

    private const val ANDROID_COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

    /** Compose UI present at runtime (checked once). */
    private val available: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        try {
            ComposeSemanticsReader.probe()
            true
        } catch (_: LinkageError) {
            false // NoClassDefFoundError: the app has no Compose
        } catch (_: Exception) {
            false
        }
    }

    private val warned = AtomicBoolean(false)

    /**
     * True for a view that hosts Compose content. Matched by type
     * (`RootForTest`, which AndroidComposeView implements; survives R8
     * renaming in the app) or, failing that, by class name, so a Compose
     * version whose root no longer has that type is still found and then
     * masked whole by [read].
     */
    fun isHost(view: View): Boolean {
        if (available) {
            try {
                if (ComposeSemanticsReader.isRoot(view)) return true
            } catch (_: LinkageError) {
            } catch (_: Exception) {
            }
        }
        return view.javaClass.name == ANDROID_COMPOSE_VIEW
    }

    /**
     * The host's semantics tree, capped at [ComposeTree.MAX_NODES] nodes and
     * [ComposeTree.MAX_DEPTH] levels. Never throws: any failure (Compose
     * missing or of an unexpected shape, the read throwing) gives
     * [ComposeTree.FAILED], logged once per process, and the caller masks
     * the whole view.
     */
    fun read(view: View): ComposeTree {
        if (!available) return failed("Compose UI classes not found")
        return try {
            ComposeSemanticsReader.read(view, ComposeTree.MAX_NODES, ComposeTree.MAX_DEPTH)
        } catch (e: LinkageError) {
            failed(e.toString())
        } catch (e: Exception) {
            failed(e.toString())
        }
    }

    private fun failed(reason: String): ComposeTree {
        if (warned.compareAndSet(false, true)) {
            Logger.w("Unilitix: Jetpack Compose semantics could not be read ($reason); Compose views are masked whole in session replay")
        }
        return ComposeTree.FAILED
    }
}
