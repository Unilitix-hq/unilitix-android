package io.unilitix.sdk.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

internal data class NetworkInfo(
    val connectionType: String,
    val carrier: String?
)

internal fun collectNetworkInfo(context: Context): NetworkInfo {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val connectionType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        when {
            caps == null -> "NONE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> resolveCellularGeneration(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }
    } else {
        @Suppress("DEPRECATION")
        when (cm.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "WIFI"
            ConnectivityManager.TYPE_MOBILE -> resolveCellularGeneration(context)
            else -> "NONE"
        }
    }

    val carrier = getCarrierName(context)

    return NetworkInfo(connectionType = connectionType, carrier = carrier)
}

private fun resolveCellularGeneration(context: Context): String {
    val hasTelephonyPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    val networkType = if (hasTelephonyPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try { tm.dataNetworkType } catch (e: SecurityException) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
    } else {
        @Suppress("DEPRECATION")
        try { tm.networkType } catch (e: SecurityException) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
    }

    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

        TelephonyManager.NETWORK_TYPE_LTE -> "4G"

        TelephonyManager.NETWORK_TYPE_NR -> "5G"

        else -> "CELLULAR"
    }
}

internal fun isWifi(context: Context): Boolean {
    return collectNetworkInfo(context).connectionType == "WIFI"
}

internal class NetworkMonitor(private val context: Context) {

    fun currentType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> resolveCellularGeneration(context)
                else -> "UNKNOWN"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            if (info == null || !info.isConnected) "OFFLINE"
            else when (info.type) {
                ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET -> "WIFI"
                ConnectivityManager.TYPE_MOBILE -> resolveCellularGeneration(context)
                else -> "UNKNOWN"
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun observeNetworkTransitions(onChange: (String) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(currentType())
            override fun onLost(network: Network) = onChange(currentType())
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(currentType())
        }
        networkCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            @Suppress("DEPRECATION")
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }
    }

    fun stopObserving() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }
}

private fun getCarrierName(context: Context): String? {
    return try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.networkOperatorName.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
