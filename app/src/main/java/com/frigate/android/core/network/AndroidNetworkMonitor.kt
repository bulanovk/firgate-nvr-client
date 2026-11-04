package com.frigate.android.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper

/**
 * Watches Wi-Fi networks, not only the default network: a home Wi-Fi without internet is still
 * home while the phone uses mobile data. The SSID is visible only with the location permission.
 */
class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((NetworkSnapshot) -> Unit)? = null
    private var wifi: Pair<String?, String?>? = null
    private var current: Network? = null
    private var lastSent: NetworkSnapshot? = null

    private val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val info = capabilities.transportInfo as? WifiInfo ?: return
            current = network
            wifi = info.ssid to info.bssid
            scheduleNotify()
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            wifi = null
            scheduleNotify()
        }
    }

    private val notify = Runnable {
        val snapshot = NetworkSnapshot(wifi?.first, wifi?.second, hasLocationPermission())
        if (snapshot != lastSent) {
            lastSent = snapshot
            listener?.invoke(snapshot)
        }
    }

    override fun start(onChange: (NetworkSnapshot) -> Unit): AutoCloseable {
        listener = onChange
        lastSent = null
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, callback, handler)
        scheduleNotify()
        return AutoCloseable {
            connectivity.unregisterNetworkCallback(callback)
            handler.removeCallbacks(notify)
            listener = null
        }
    }

    override fun refresh() = scheduleNotify()

    /** Several callbacks arrive in a burst on a network change; only the settled state is reported. */
    private fun scheduleNotify() {
        handler.removeCallbacks(notify)
        handler.postDelayed(notify, DEBOUNCE_MS)
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
