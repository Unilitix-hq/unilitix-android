package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import io.unilitix.sdk.Unilitix
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.UnilitixPrivate
import io.unilitix.sdk.core.Snapshot
import io.unilitix.sdk.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * View-tree snapshots of the resumed activity's window.
 *
 * A main-looper tick runs four times a second while an activity is resumed.
 * [SnapshotScheduler] decides when the tree is walked: once per
 * `snapshotIntervalMs` (clamped to 1–60 s), and as soon as the interval
 * allows after a navigation (an activity resuming). A tree identical to the
 * last one taken in the session (same screen and viewport) is dropped, and
 * only kept snapshots take an ordinal. Same behaviour as UnilitixCore (iOS).
 *
 * The walk ([SnapshotBuilder] over [ViewSnapshotNode]) runs on the main
 * thread, as the View system requires, masks text as it goes and produces
 * an immutable [SnapshotNode] tree capped at [SnapshotBuilder.MAX_NODES]
 * nodes and [SnapshotBuilder.MAX_DEPTH] levels. Only the JSON encoding and
 * the hand-off happen on the IO scope.
 *
 * Coordinates: `bounds` and `viewportWidth`/`viewportHeight` are in dp,
 * relative to the window's top-left corner.
 *
 * Jetpack Compose: an `AndroidComposeView` has its semantics nodes
 * ([ComposeSnapshotNode], via [ComposeHost]) as children, before any
 * Android views it hosts.
 */
internal class SnapshotCapture(
    private val config: UnilitixConfig,
    /** False while consent is denied: nothing is captured. */
    private val isEnabled: () -> Boolean = { true },
    /** The current session's id; a change restarts the schedule and dedupe. */
    private val sessionId: () -> String? = { null },
    private val onSnapshot: (Snapshot) -> Unit
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ordinal = AtomicInteger(0)

    private val maskPolicy = MaskPolicy(
        maskInputs              = config.maskInputs,
        maskInputsInScreenshots = config.maskInputsInScreenshots,
        maskAllText             = config.maskAllText,
        maskImages              = config.maskImages,
    )
    private var currentActivity: Activity? = null

    // Main thread only. Interval clamped to [1s, 60s] — never fast enough to cause jank.
    private val scheduler = SnapshotScheduler(config.snapshotIntervalMs)

    private val tickRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { tick(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** New session: ordinals restart and the per-session cap is counted afresh. */
    fun resetSession() {
        ordinal.set(0)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        scheduler.markNavigation() // a new screen, or back from the background
        handler.removeCallbacks(tickRunnable)
        // One tick later, so the first layout pass of a new activity has run.
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onActivityPaused(activity: Activity) {
        handler.removeCallbacks(tickRunnable)
        currentActivity = null
    }

    /** Main thread. */
    private fun tick(activity: Activity) {
        if (!isEnabled()) return
        // maxSnapshotsPerSession: stop for the rest of this session.
        if (ordinal.get() >= config.maxSnapshotsPerSession) return
        if (ReplayMasking.isSecureWindow(activity.window.attributes.flags)) return
        val rootView = activity.window.decorView
        // Not laid out yet: keep a pending navigation for the next tick.
        if (rootView.width <= 0 || rootView.height <= 0) return

        val now = SystemClock.uptimeMillis()
        if (!scheduler.shouldWalk(now, sessionId() ?: "")) return

        try {
            val window = WindowGeometry.windowPixels(activity)
            if (window.right - window.left <= 0 || window.bottom - window.top <= 0) return
            val density = WindowGeometry.density(activity)
            val origin = intArrayOf(window.left, window.top)

            val tree = SnapshotBuilder.build(ViewSnapshotNode(rootView, origin), density, maskPolicy) ?: return
            val viewportWidth = Dp.of(window.right - window.left, density)
            val viewportHeight = Dp.of(window.bottom - window.top, density)
            val screenName = activity.javaClass.simpleName

            if (!scheduler.accept(tree, screenName, viewportWidth, viewportHeight, now)) return
            val captureOrdinal = ordinal.getAndIncrement()
            if (captureOrdinal >= config.maxSnapshotsPerSession) return
            val capturedAt = System.currentTimeMillis()

            // Off the main thread: only encoding and the hand-off; the tree is immutable.
            captureScope.launch {
                try {
                    onSnapshot(
                        Snapshot(
                            capturedAt     = capturedAt,
                            ordinal        = captureOrdinal,
                            screenName     = screenName,
                            viewportWidth  = viewportWidth,
                            viewportHeight = viewportHeight,
                            hierarchy      = tree.toJson()
                        )
                    )
                } catch (e: Exception) {
                    Logger.w("SnapshotCapture: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.w("SnapshotCapture: ${e.message}")
        }
    }

    /**
     * Adapts a View to [SnapshotBuilder]. Main thread only, during the walk;
     * properties are read on demand so text is read only for text and input
     * views, and nothing is read for skipped or private views.
     */
    private inner class ViewSnapshotNode(private val view: View, private val origin: IntArray) : SnapshotViewNode {
        // A Jetpack Compose host: its semantics, read at most once per walk.
        private val compose: ComposeTree? by lazy(LazyThreadSafetyMode.NONE) {
            if (ComposeHost.isHost(view)) ComposeHost.read(view) else null
        }
        /** Compose semantics could not be read: one node, masked like a web view. */
        override val isUnreadable: Boolean get() = compose?.failed ?: false
        override val typeName: String get() = view.javaClass.simpleName
        override val kind: NodeKind
            get() = when (view) {
                is EditText -> NodeKind.INPUT
                is TextView -> NodeKind.TEXT
                is ImageView -> NodeKind.IMAGE
                else -> NodeKind.OTHER
            }
        override val isWebView: Boolean get() = view is WebView
        override val isGone: Boolean get() = view.visibility == View.GONE
        override val isVisible: Boolean get() = view.visibility == View.VISIBLE
        override val isPrivate: Boolean get() = view.javaClass.isAnnotationPresent(UnilitixPrivate::class.java)
        override val isMaskedById: Boolean get() = view.id != View.NO_ID && view.id in config.maskedViewIds
        override val isUnmasked: Boolean get() = Unilitix.isUnmasked(view)
        override val isPassword: Boolean
            get() {
                val inputType = (view as? EditText)?.inputType ?: return false
                return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    ((inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                     (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                    (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                    (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
        override val frame: MaskRect
            get() {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val x = loc[0] - origin[0]
                val y = loc[1] - origin[1]
                return MaskRect(x, y, x + view.width, y + view.height)
            }
        override val text: String? get() = (view as? TextView)?.text?.toString()
        override val textColor: String?
            get() = (view as? TextView)?.let { hex(it.currentTextColor) }
        override val backgroundColor: String? get() = extractBackgroundColor(view)
        override val isInteractive: Boolean
            get() = view is Button || view.hasOnClickListeners() || view.isClickable
        override val children: List<SnapshotViewNode>
            get() {
                val group = view as? ViewGroup
                val views: List<SnapshotViewNode> =
                    if (group == null) emptyList() else List(group.childCount) { ViewSnapshotNode(group.getChildAt(it), origin) }
                val root = compose?.root ?: return views
                // Compose nodes are relative to the host's top-left.
                val f = frame
                return listOf<SnapshotViewNode>(ComposeSnapshotNode(root, f.left, f.top)) + views
            }
    }

    // Reflection lookups are cached (main thread only): the walk must stay cheap.
    private val fillColorFields = HashMap<Class<*>, Field?>()
    private val gradientFillPaint: Field? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            GradientDrawable::class.java.getDeclaredField("mFillPaint").also { it.isAccessible = true }
        } catch (_: Exception) {
            null
        }
    }

    private fun hex(color: Int): String = "#%06X".format(0xFFFFFF and color)

    private fun extractBackgroundColor(view: View): String? {
        // Strategy 1: plain ColorDrawable
        (view.background as? ColorDrawable)?.let {
            return "#%06X".format(0xFFFFFF and it.color)
        }

        // Strategy 2: backgroundTintList — try enabled state first, then default
        //   (MaterialButton stores primary color here for API < 21 fallback path)
        view.backgroundTintList?.let { tint ->
            val color = tint.getColorForState(
                intArrayOf(android.R.attr.state_enabled),
                tint.defaultColor
            )
            if (color != 0 && color != -1) {
                return "#%06X".format(0xFFFFFF and color)
            }
        }

        // Strategy 3: RippleDrawable (Material 2 buttons wrap a shape in a ripple)
        (view.background as? RippleDrawable)?.let { ripple ->
            colorFromLayerDrawable(ripple)?.let { return it }
        }

        // Strategy 4: LayerDrawable wrapping something colored (some custom themes)
        (view.background as? LayerDrawable)?.let { layer ->
            colorFromLayerDrawable(layer)?.let { return it }
        }

        // Strategy 5: GradientDrawable with a solid fill
        colorFromGradientDrawable(view.background as? GradientDrawable)?.let { return it }

        // Strategy 6: MaterialButton-specific — read fillColor via reflection from
        //   MaterialShapeDrawable, which is the actual background for Material 3 buttons
        try {
            val bg = view.background ?: return null
            val cls = bg.javaClass
            val fillColorField = if (fillColorFields.containsKey(cls)) {
                fillColorFields[cls]
            } else {
                val f = try {
                    cls.getDeclaredField("fillColor").also { it.isAccessible = true }
                } catch (_: Exception) {
                    null
                }
                fillColorFields[cls] = f // misses are cached too
                f
            } ?: return null
            val csl = fillColorField.get(bg) as? ColorStateList ?: return null
            val color = csl.getColorForState(
                intArrayOf(android.R.attr.state_enabled),
                csl.defaultColor
            )
            if (color != 0 && color != -1) {
                return "#%06X".format(0xFFFFFF and color)
            }
        } catch (_: Exception) {}

        return null
    }

    private fun colorFromLayerDrawable(layer: LayerDrawable): String? {
        for (i in 0 until layer.numberOfLayers) {
            val d = layer.getDrawable(i)
            when {
                d is ColorDrawable -> return "#%06X".format(0xFFFFFF and d.color)
                d is GradientDrawable -> colorFromGradientDrawable(d)?.let { return it }
            }
        }
        return null
    }

    private fun colorFromGradientDrawable(d: GradientDrawable?): String? {
        d ?: return null
        return try {
            // mFillPaint is populated after the drawable is drawn at least once
            val field = gradientFillPaint ?: return null
            val paint = field.get(d) as? android.graphics.Paint
            val color = paint?.color ?: return null
            if (color != 0 && color != -1) "#%06X".format(0xFFFFFF and color) else null
        } catch (_: Exception) {
            null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        /** How often the scheduler is asked (as iOS); walking happens only when it says so. */
        private const val TICK_MS = 250L
    }
}

/** The hierarchy JSON; null fields are left out. */
internal fun SnapshotNode.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("type", type)
    obj.put("bounds", JSONObject().apply {
        put("x", bounds.x)
        put("y", bounds.y)
        put("w", bounds.w)
        put("h", bounds.h)
    })
    text?.let { obj.put("text", it) }
    redacted?.let { obj.put("redacted", it) }
    textColor?.let { obj.put("textColor", it) }
    bgColor?.let { obj.put("bgColor", it) }
    isPassword?.let { obj.put("isPassword", it) }
    inputType?.let { obj.put("inputType", it) }
    interactive?.let { obj.put("interactive", it) }
    isImage?.let { obj.put("isImage", it) }
    masked?.let { obj.put("masked", it) }
    hidden?.let { obj.put("hidden", it) }
    children?.let { kids ->
        val arr = JSONArray()
        for (k in kids) arr.put(k.toJson())
        obj.put("children", arr)
    }
    return obj
}
