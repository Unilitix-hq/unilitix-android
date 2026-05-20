package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.core.Snapshot
import io.unilitix.sdk.util.Logger
import org.json.JSONArray
import org.json.JSONObject

internal class SnapshotCapture(
    private val config: UnilitixConfig,
    private val onSnapshot: (Snapshot) -> Unit
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var ordinal = 0
    private var currentActivity: Activity? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { capture(it) }
            handler.postDelayed(this, config.snapshotIntervalMs)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        handler.postDelayed(captureRunnable, config.snapshotIntervalMs)
    }

    override fun onActivityPaused(activity: Activity) {
        handler.removeCallbacks(captureRunnable)
        currentActivity = null
    }

    private fun capture(activity: Activity) {
        val rootView = activity.window.decorView

        // Use full physical display size so viewport coordinates match getLocationOnScreen.
        // decorView.height excludes the nav bar on non-edge-to-edge builds, causing buttons
        // near the bottom to have y > viewportHeight when the dashboard scales the frame.
        val viewportWidth: Int
        val viewportHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            viewportWidth = bounds.width()
            viewportHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(dm)
            viewportWidth = dm.widthPixels
            viewportHeight = dm.heightPixels
        }

        try {
            val hierarchy = serializeView(rootView, 0) ?: return

            Logger.d("=== FRAME CAPTURE VERIFICATION ===")
            Logger.d("Device screen: ${viewportWidth}x${viewportHeight}, root view: ${rootView.width}x${rootView.height}")
            logButtons(hierarchy, viewportHeight)
            Logger.d("===================================")

            val snapshot = Snapshot(
                capturedAt = System.currentTimeMillis(),
                ordinal = ordinal++,
                screenName = activity.javaClass.simpleName,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                hierarchy = hierarchy
            )
            onSnapshot(snapshot)
        } catch (e: Exception) {
            Logger.w("SnapshotCapture: ${e.message}")
        }
    }

    private fun logButtons(node: JSONObject, viewportHeight: Int) {
        val type = node.optString("type", "")
        if (type.contains("Button", ignoreCase = true)) {
            val bounds = node.optJSONObject("bounds")
            val y = bounds?.optInt("y", 0) ?: 0
            val h = bounds?.optInt("h", 0) ?: 0
            val text = node.optString("text", "")
            val ratio = if (viewportHeight > 0) "%.3f".format(y.toFloat() / viewportHeight) else "?"
            Logger.d("Button '$text' at y=$y h=$h ratio=$ratio (bottom=${y + h}, viewport=$viewportHeight)")
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            children.optJSONObject(i)?.let { logButtons(it, viewportHeight) }
        }
    }

    private fun serializeView(view: View, depth: Int): JSONObject? {
        // GONE views have no layout — skip entirely so they don't pollute the tree.
        // INVISIBLE views still occupy space and may wrap visible children — capture them.
        if (view.visibility == View.GONE) return null
        if (view.width <= 0 || view.height <= 0) return null

        val obj = JSONObject()
        obj.put("type", view.javaClass.simpleName)

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        obj.put("bounds", JSONObject().apply {
            put("x", location[0])
            put("y", location[1])
            put("w", view.width)
            put("h", view.height)
        })

        if (view.visibility != View.VISIBLE) {
            // INVISIBLE: preserve bounds for layout accuracy, mark hidden, still recurse below
            obj.put("hidden", true)
        } else {
            val isMaskedById = view.id != View.NO_ID && view.id in config.maskedViewIds

            when {
                view is EditText -> {
                    val inputType = view.inputType
                    val isPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                        ((inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                         (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                        (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    val text = when {
                        isMaskedById || config.maskInputs || isPassword -> "[MASKED]"
                        else -> view.text.toString().take(200)
                    }
                    obj.put("text", text)
                    obj.put("isPassword", isPassword)
                    obj.put("inputType", "edit")
                    obj.put("interactive", true)
                }
                view is Button -> {
                    obj.put("text", if (isMaskedById) "[MASKED]" else view.text.toString().take(200))
                    obj.put("textColor", "#%06X".format(0xFFFFFF and view.currentTextColor))
                    obj.put("interactive", true)
                }
                view is TextView -> {
                    obj.put("text", if (isMaskedById) "[MASKED]" else view.text.toString().take(200))
                    obj.put("textColor", "#%06X".format(0xFFFFFF and view.currentTextColor))
                }
                view is ImageView -> obj.put("isImage", true)
            }

            extractBackgroundColor(view)?.let { obj.put("bgColor", it) }

            if (view.hasOnClickListeners() || view.isClickable) {
                obj.put("interactive", true)
            }

            if (isMaskedById) {
                obj.put("masked", true)
                obj.remove("text")
            }
        }

        // No depth cap — traverse the full tree regardless of nesting depth
        if (view is ViewGroup && view.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                serializeView(view.getChildAt(i), depth + 1)?.let { children.put(it) }
            }
            if (children.length() > 0) obj.put("children", children)
        }

        return obj
    }

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
        if (Build.VERSION.SDK_INT >= 21) {
            (view.background as? RippleDrawable)?.let { ripple ->
                colorFromLayerDrawable(ripple)?.let { return it }
            }
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
            val fillColorField = bg.javaClass.getDeclaredField("fillColor")
            fillColorField.isAccessible = true
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
            val field = GradientDrawable::class.java.getDeclaredField("mFillPaint")
            field.isAccessible = true
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
}
