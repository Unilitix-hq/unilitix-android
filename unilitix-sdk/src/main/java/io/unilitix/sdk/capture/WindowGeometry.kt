package io.unilitix.sdk.capture

import android.app.Activity
import android.os.Build
import android.util.DisplayMetrics

/**
 * The window's rectangle on screen, in pixels: the shared coordinate space
 * of snapshots, taps and screenshots. Its top-left corner is the origin of
 * every snapshot bound and tap position; its size (in dp) is the viewport.
 * On API 30+ this is the window's own bounds (correct in split screen);
 * before, the full physical display (decorView.height excludes the nav bar
 * on some non-edge-to-edge builds).
 */
internal object WindowGeometry {
    fun windowPixels(activity: Activity): MaskRect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = activity.windowManager.currentWindowMetrics.bounds
            MaskRect(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(dm)
            MaskRect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    fun density(activity: Activity): Float = activity.resources.displayMetrics.density
}
