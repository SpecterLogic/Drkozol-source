package com.v2ray.ang.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.atomic.AtomicBoolean

/** Android transport observation, never proof that a native core is running. */
internal class VpnNetworkSnapshot<T> {
    private val networks = mutableSetOf<T>()
    @Synchronized fun available(network: T) { networks.add(network) }
    @Synchronized fun lost(network: T) { networks.remove(network) }
    @Synchronized fun active(): Boolean = networks.isNotEmpty()
}

/**
 * Each Application process owns one callback for its lifetime. The VPN excludes our own UID,
 * so activeNetwork is the direct upstream even while other apps use the tunnel. Observing
 * VPN transport avoids starting standalone peer probes over an already active tunnel.
 */
object VpnTransportState {
    private val registered = AtomicBoolean(false)
    private val snapshot = VpnNetworkSnapshot<Network>()
    val active: Boolean get() = snapshot.active()

    fun register(applicationContext: Context) {
        if (!registered.compareAndSet(false, true)) return
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { snapshot.available(network) }
            override fun onLost(network: Network) { snapshot.lost(network) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) snapshot.available(network)
                else snapshot.lost(network)
            }
        }
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
        } catch (error: Exception) {
            registered.set(false)
            LogUtil.e(AppConfig.TAG, "VPN transport observer registration failed component=Application", error)
        }
    }
}
