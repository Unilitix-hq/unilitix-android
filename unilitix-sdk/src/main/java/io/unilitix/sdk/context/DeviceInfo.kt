package io.unilitix.sdk.context

import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import io.unilitix.sdk.BuildConfig
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val os: String,
    val osVersion: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenDensity: Float,
    val orientation: String,
    val locale: String,
    val timezone: String,
    val packageName: String,
    val appVersion: String,
    val buildNumber: String,
    val sdkVersion: String,
    val installId: String,
    val totalStorageGb: Double
)

internal fun collectDeviceInfo(context: Context): DeviceInfo {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Use full physical display size — getMetrics() excludes nav bar and gives wrong height.
    val screenWidth: Int
    val screenHeight: Int
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        "LANDSCAPE"
    } else {
        "PORTRAIT"
    }

    val appVersion = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    val buildNumber = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        "0"
    }

    val installId = getOrCreateInstallId(context)

    val totalStorageGb = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        (stat.blockSizeLong * stat.blockCountLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
    } catch (_: Exception) { 0.0 }

    return DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        os = "Android",
        osVersion = Build.VERSION.RELEASE,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        screenDensity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.resources.displayMetrics.density
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.density
        },
        orientation = orientation,
        locale = Locale.getDefault().toLanguageTag(),
        timezone = TimeZone.getDefault().id,
        packageName = context.packageName,
        appVersion = appVersion,
        buildNumber = buildNumber,
        sdkVersion = BuildConfig.SDK_VERSION,
        installId = installId,
        totalStorageGb = totalStorageGb
    )
}

private fun getOrCreateInstallId(context: Context): String {
    val prefs = context.getSharedPreferences("unilitix_sdk_prefs", Context.MODE_PRIVATE)
    var id = prefs.getString("install_id", null)
    if (id == null) {
        id = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", id).apply()
    }
    return id
}
