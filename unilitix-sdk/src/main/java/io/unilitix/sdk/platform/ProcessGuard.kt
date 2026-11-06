package io.unilitix.sdk.platform

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Multi-process apps (1.11.0): the SDK runs in the app's main process only.
 * Its queue database, preferences and crash handler are per process; a second
 * process (a `:remote` service, a sync adapter) initialising it would start
 * sessions of its own and race the main process for the same files.
 */
internal object ProcessGuard {

    fun isMainProcess(app: Application): Boolean {
        val current = currentProcessName(app) ?: return true // unknown: do not disable the SDK
        val main = app.applicationInfo?.processName ?: app.packageName
        return current == main
    }

    fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { return Application.getProcessName() } catch (_: Throwable) {}
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName?.let { return it }
        } catch (_: Throwable) {}
        return try {
            File("/proc/self/cmdline").readText().trimEnd('\u0000').substringBefore('\u0000').takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }
}
