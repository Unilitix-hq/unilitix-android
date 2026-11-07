package io.unilitix.sdk.capture

import android.app.Activity
import android.view.View
import androidx.fragment.app.Fragment
import io.unilitix.sdk.R
import io.unilitix.sdk.UnilitixPrivate
import io.unilitix.sdk.util.Logger
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Which views replay must hide and taps must skip: a view whose class is
 * annotated [UnilitixPrivate], a view marked with `Unilitix.mask(view)`, and
 * (1.11.0) a view held in an Activity or Fragment field annotated
 * `@UnilitixPrivate` ([scan] marks those).
 */
internal object PrivacyTags {

    fun isPrivate(view: View): Boolean {
        if (view.javaClass.isAnnotationPresent(UnilitixPrivate::class.java)) return true
        val tag = view.getTag(R.id.unilitix_private) ?: return false
        return tag == true || tag.toString().equals("true", ignoreCase = true)
    }

    fun mark(view: View, private: Boolean) {
        view.setTag(R.id.unilitix_private, if (private) true else null)
    }

    /** Annotated View fields per class (walked up to, not including, framework classes). */
    private val fieldsByClass = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun annotatedFields(type: Class<*>): List<Field> = fieldsByClass.getOrPut(type) {
        val out = ArrayList<Field>()
        var c: Class<*>? = type
        while (c != null && !isFramework(c)) {
            for (f in c.declaredFields) {
                if (f.isAnnotationPresent(UnilitixPrivate::class.java)) {
                    try {
                        f.isAccessible = true
                        out.add(f)
                    } catch (e: Exception) {
                        Logger.d("PrivacyTags: field ${f.name} not readable: ${e.message}")
                    }
                }
            }
            c = c.superclass
        }
        out
    }

    private fun isFramework(c: Class<*>): Boolean {
        val n = c.name
        return n.startsWith("android.") || n.startsWith("androidx.") || n.startsWith("java.") ||
            n.startsWith("kotlin.") || n.startsWith("com.google.android.material.")
    }

    /** Marks the views held in [owner]'s `@UnilitixPrivate` fields (an Activity or Fragment). Main thread. */
    fun scan(owner: Any) {
        try {
            for (f in annotatedFields(owner.javaClass)) {
                when (val v = try { f.get(owner) } catch (e: Exception) { null }) {
                    is View -> mark(v, true)
                    is Iterable<*> -> v.forEach { (it as? View)?.let { view -> mark(view, true) } }
                    is Array<*> -> v.forEach { (it as? View)?.let { view -> mark(view, true) } }
                }
            }
        } catch (t: Throwable) {
            Logger.d("PrivacyTags: scan of ${owner.javaClass.simpleName} failed: ${t.message}")
        }
    }

    fun scanActivity(activity: Activity) = scan(activity)

    fun scanFragment(fragment: Fragment) = scan(fragment)
}
