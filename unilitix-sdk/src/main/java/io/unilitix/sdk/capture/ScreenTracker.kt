package io.unilitix.sdk.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.unilitix.sdk.util.Logger

internal class ScreenTracker(
    private val onScreen: (String) -> Unit
) : Application.ActivityLifecycleCallbacks {

    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            val name = f.javaClass.simpleName
            Logger.d("ScreenTracker: fragment resumed $name")
            onScreen(name)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val name = activity.javaClass.simpleName
        Logger.d("ScreenTracker: activity resumed $name")
        onScreen(name)

        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
