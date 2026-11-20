package io.github.designarena.wrapper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Observes validated internet connectivity so the offline recovery view can auto-dismiss
 * when mobile data or Wi-Fi reconnects.
 */
class NetworkMonitor(
    context: Context,
    private val onConnectivityChanged: (Boolean) -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onConnectivityChanged(true)
        }

        override fun onLost(network: Network) {
            onConnectivityChanged(isCurrentlyOnline())
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            onConnectivityChanged(online)
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching {
            cm.unregisterNetworkCallback(callback)
        }
        registered = false
    }

    fun isCurrentlyOnline(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
