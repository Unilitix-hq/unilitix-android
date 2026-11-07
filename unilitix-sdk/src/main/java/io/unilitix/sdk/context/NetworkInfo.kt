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
import io.unilitix.sdk.engine.NetworkSnapshot
import io.unilitix.sdk.engine.NetworkSource
import io.unilitix.sdk.engine.NetworkTypes
import io.unilitix.sdk.util.Logger

/**
 * The device's network as the contract's `networkTypes` (1.4.0):
 * `WIFI ETHERNET 5G 4G 3G 2G CELLULAR OFFLINE UNKNOWN`, through
 * [NetworkTypes.canonical]:
 * - transport: Wi-Fi, Ethernet (Android TV, emulators, USB adapters: not
 *   Wi-Fi), cellular, none (no default network), other (VPN-only, Bluetooth);
 * - generation (cellular): `TelephonyManager` data network type, 5G for NR;
 *   unknown without READ_PHONE_STATE (`CELLULAR`);
 * - online: the default network is VALIDATED (API 23+). An unvalidated
 *   network (captive portal) reads `OFFLINE`, but an upload is still
 *   attempted when it has the INTERNET capability ([NetworkSnapshot.reachable]):
 *   some networks never validate (Google's check host blocked).
 */
internal class NetworkMonitor(private val context: Context) : NetworkSource {

    /** The network now. Never throws (it runs for every event and on the ConnectivityThread). */
    override fun current(): NetworkSnapshot = try {
        currentUnsafe()
    } catch (t: Throwable) {
        NetworkSnapshot(NetworkTypes.UNKNOWN, online = true, reachable = true)
    }

    /** Contract value of [current]. */
    fun currentType(): String = current().type

    private fun currentUnsafe(): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSnapshot(NetworkTypes.UNKNOWN, online = true, reachable = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return NetworkSnapshot(NetworkTypes.OFFLINE, online = false, reachable = false)
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = internet && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val generation = if (transport == "cellular") cellularGeneration(context) else null
            return NetworkSnapshot(NetworkTypes.canonical(transport, generation, validated), validated, internet)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        if (info == null || !info.isConnected) return NetworkSnapshot(NetworkTypes.OFFLINE, online = false, reachable = false)
        @Suppress("DEPRECATION")
        val transport = when (info.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            else -> "other"
        }
        val generation = if (transport == "cellular") cellularGeneration(context) else null
        return NetworkSnapshot(NetworkTypes.canonical(transport, generation, true), online = true, reachable = true)
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Calls [onChange] when the default network changes (transitions are
     * counted by the engine, and a reconnect starts an upload). Returns false
     * when the callback could not be registered: some Android 11 builds throw
     * SecurityException, and an app holding the maximum number of callbacks
     * gets TooManyRequestsException. The SDK then carries on; [current] is
     * still read per event.
     */
    fun observe(onChange: () -> Unit): Boolean {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report()
            override fun onLost(network: Network) = report()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report()

            // Runs on the system's ConnectivityThread: must never throw.
            private fun report() {
                try { onChange() } catch (_: Throwable) {}
            }
        }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            networkCallback = callback
            true
        } catch (t: Throwable) {
            networkCallback = null
            Logger.d("NetworkMonitor: network callback not registered (${t.javaClass.simpleName}: ${t.message})")
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

    companion object {
        /** `5g`, `4g`, `3g`, `2g` or `unknown` (contract generation names). */
        @SuppressLint("MissingPermission")
        fun cellularGeneration(context: Context): String {
            val hasPermission = try {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "unknown"
            val type = try {
                if (hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType
                else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    tm.networkType
                } else TelephonyManager.NETWORK_TYPE_UNKNOWN
            } catch (e: SecurityException) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            return generationOf(type)
        }

        /** TelephonyManager network type → contract generation. */
        fun generationOf(networkType: Int): String = when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            16 /* NETWORK_TYPE_GSM */ -> "2g"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            17 /* NETWORK_TYPE_TD_SCDMA */ -> "3g"

            TelephonyManager.NETWORK_TYPE_LTE,
            18 /* NETWORK_TYPE_IWLAN */,
            19 /* NETWORK_TYPE_LTE_CA */ -> "4g"

            20 /* NETWORK_TYPE_NR */ -> "5g"

            else -> "unknown"
        }
    }
}

/** The carrier's name, or null. */
internal fun carrierName(context: Context): String? = try {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    tm.networkOperatorName.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** True when the network is Wi-Fi (or Ethernet: unmetered for screenshot uploads). */
internal fun isWifi(context: Context): Boolean {
    val t = NetworkMonitor(context).currentType()
    return t == NetworkTypes.WIFI || t == NetworkTypes.ETHERNET
}
