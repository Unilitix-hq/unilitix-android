package io.unilitix.sdk.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.unilitix.sdk.util.Logger

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
            caps == null -> "OFFLINE"
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
            else -> "OFFLINE"
        }
    }

    val carrier = getCarrierName(context)

    return NetworkInfo(connectionType = connectionType, carrier = carrier)
}

@SuppressLint("MissingPermission")
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

    /**
     * The network type now. Never throws: it runs for every event and on the
     * system's ConnectivityThread, where an exception would crash the host.
     */
    fun currentType(): String = try {
        currentTypeUnsafe()
    } catch (t: Throwable) {
        "UNKNOWN"
    }

    private fun currentTypeUnsafe(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "UNKNOWN"
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

    /**
     * Counts network transitions through a ConnectivityManager callback.
     * Returns false when the callback could not be registered: some Android 11
     * builds throw SecurityException, and an app already holding the maximum
     * number of callbacks gets TooManyRequestsException. The SDK then carries
     * on without transition counts; [currentType] is still read per event.
     */
    fun observeNetworkTransitions(onChange: (String) -> Unit): Boolean {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report()
            override fun onLost(network: Network) = report()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report()

            // Runs on the system's ConnectivityThread: must never throw.
            private fun report() {
                try { onChange(currentType()) } catch (_: Throwable) {}
            }
        }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                @Suppress("DEPRECATION")
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            networkCallback = callback
            true
        } catch (t: Throwable) {
            networkCallback = null
            Logger.d("NetworkMonitor: network callback not registered (${t.javaClass.simpleName}: ${t.message}); network transitions will not be counted")
            false
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
