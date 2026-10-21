package com.valt.offlinensdshare.nsd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress

// The app only works inside one Wi-Fi, wired LAN or hotspot, never over mobile data.

/**
 * Finds the Wi-Fi or Ethernet network whose own subnet contains [host], or null if this device
 * isn't on the hub's network. Mobile data and VPNs never count.
 *
 * When Wi-Fi or Ethernet has no internet, Android makes mobile data the default network. An
 * unbound socket follows the default, so a connection to a LAN address would leave over mobile
 * data and never reach the hub. Sockets created from the returned network stay on the LAN.
 */
@Suppress("DEPRECATION") // allNetworks is the only synchronous way to list every network.
fun findLocalNetwork(context: Context, host: InetAddress): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return connectivity.allNetworks.firstOrNull { network ->
        connectivity.isWifiOrEthernet(network) &&
            // A default route would match any address; the LAN has a direct route to its subnet.
            connectivity.getLinkProperties(network)?.routes.orEmpty()
                .any { !it.isDefaultRoute && it.matches(host) }
    }
}

/** True if [address] is one of this device's own mobile data addresses. */
@Suppress("DEPRECATION")
fun isMobileDataAddress(context: Context, address: InetAddress): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivity.allNetworks.any { network ->
        connectivity.hasTransport(network, NetworkCapabilities.TRANSPORT_CELLULAR) &&
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().any { it.address == address }
    }
}

/**
 * Calls [onNewNetwork] when a Wi-Fi or Ethernet network comes up after [start], e.g. when the
 * phone joins the Wi-Fi late or rejoins it after dropping. Networks already up don't count.
 * The callback arrives on a background thread.
 */
class LocalNetworkWatcher(context: Context, private val onNewNetwork: () -> Unit) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Suppress("DEPRECATION")
    fun start() {
        if (callback != null) return
        // Registering first reports every network that is already up; only later ones are new.
        val alreadyUp = connectivity.allNetworks.toMutableSet()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!alreadyUp.remove(network)) onNewNetwork()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // with or without internet
            .build()
        callback = networkCallback
        connectivity.registerNetworkCallback(request, networkCallback)
    }

    fun stop() {
        val current = callback ?: return
        callback = null
        connectivity.unregisterNetworkCallback(current)
    }
}

private fun ConnectivityManager.isWifiOrEthernet(network: Network) =
    !hasTransport(network, NetworkCapabilities.TRANSPORT_VPN) &&
        (hasTransport(network, NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(network, NetworkCapabilities.TRANSPORT_ETHERNET))

private fun ConnectivityManager.hasTransport(network: Network, transport: Int) =
    getNetworkCapabilities(network)?.hasTransport(transport) == true
