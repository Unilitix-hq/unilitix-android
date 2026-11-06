package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.EditText
import io.unilitix.sdk.util.Logger

internal class TouchTracker(
    private val maskInputs: Boolean,
    private val maskedViewIds: Set<Int>,
    /** [x]/[y] in dp (logical px) relative to the window origin, like snapshot bounds. */
    private val onTap: (screen: String, x: Int, y: Int, viewId: String) -> Boolean
) : Application.ActivityLifecycleCallbacks {

    private val wrappedCallbacks = mutableMapOf<Activity, Window.Callback>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        installCallback(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        wrappedCallbacks.remove(activity)
    }

    private fun installCallback(activity: Activity) {
        try {
            val window = activity.window ?: return
            val original = window.callback ?: return
            // Already ours (attachToActivity after onActivityCreated, or a
            // second init): wrapping again would report every tap twice.
            if (original is TapCallback || wrappedCallbacks.containsKey(activity)) return
            val wrapper = TapCallback(original, activity, activity.javaClass.simpleName)
            window.callback = wrapper
            wrappedCallbacks[activity] = wrapper
            Logger.d("TouchTracker: installed on ${activity.javaClass.simpleName}")
        } catch (t: Throwable) {
            Logger.d("TouchTracker: not installed on ${activity.javaClass.simpleName}: ${t.message}")
        }
    }

    /** The SDK's Window.Callback wrapper; a named type so it can be recognised. */
    private inner class TapCallback(
        private val original: Window.Callback,
        private val activity: Activity,
        private val screenName: String
    ) : Window.Callback by original {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event?.action == MotionEvent.ACTION_DOWN) {
                // Inside the host's dispatchTouchEvent: the SDK's hit test must
                // never throw into it, and the event is always passed on.
                try {
                    handleTap(activity, event, screenName)
                } catch (t: Throwable) {
                    Logger.d("TouchTracker: tap not recorded: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            return original.dispatchTouchEvent(event)
        }
    }

    private fun handleTap(activity: Activity, event: MotionEvent, screenName: String) {
        val x = event.rawX
        val y = event.rawY

        val touchedView = findViewAtPoint(activity, x, y)

        // A private view, or one inside a private view, is never recorded.
        var v: View? = touchedView
        while (v != null) {
            if (PrivacyTags.isPrivate(v)) return
            v = v.parent as? View
        }

        if (touchedView != null) {
            if (maskedViewIds.contains(touchedView.id)) {
                Logger.d("TouchTracker: tap on masked view id=${touchedView.id}, skipping")
                return
            }
            if (maskInputs && isPasswordField(touchedView)) {
                Logger.d("TouchTracker: tap on password field, skipping")
                return
            }
        }

        val viewDesc = touchedView?.let {
            val resName = try {
                activity.resources.getResourceEntryName(it.id)
            } catch (e: Exception) {
                it.javaClass.simpleName
            }
            resName
        } ?: "unknown"

        // Hit-testing above uses raw screen pixels; the event carries logical px
        // relative to the window, the space of snapshot bounds and viewports.
        val window = WindowGeometry.windowPixels(activity)
        val density = WindowGeometry.density(activity)
        onTap(screenName, Dp.point(x, window.left, density), Dp.point(y, window.top, density), viewDesc)
    }

    private fun findViewAtPoint(activity: Activity, x: Float, y: Float): View? {
        val root = activity.window.decorView.rootView
        return findViewRecursive(root, x.toInt(), y.toInt())
    }

    private fun findViewRecursive(view: View, x: Int, y: Int): View? {
        if (!view.isShown) return null

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height

        if (x < left || x > right || y < top || y > bottom) return null

        if (view is android.view.ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val hit = findViewRecursive(child, x, y)
                if (hit != null) return hit
            }
        }

        return view
    }

    private fun isPasswordField(view: View): Boolean {
        if (view !is EditText) return false
        val inputType = view.inputType
        return (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
               (inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
               (inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
               (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
