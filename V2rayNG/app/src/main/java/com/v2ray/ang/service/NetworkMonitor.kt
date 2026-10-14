package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: (NetworkMonitor) -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    private var upstream = UpstreamState<Network>()
    private val callbackLock = Any()
    private var owner: CoroutineScope? = null
    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(callbackLock) {
                if (!registered) return
                val reload = upstream.available(network)
                onUnderlyingNetworksChanged(arrayOf(network))
                if (reload) scheduleHandover(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(callbackLock) {
                if (registered && upstream.current == network) onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            synchronized(callbackLock) {
                if (registered && upstream.lost(network)) {
                    handoverJob?.cancel()
                    onUnderlyingNetworksChanged(null)
                }
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(callbackLock) {
            if (registered) return
            owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            registered = true
            try {
                connectivity.requestNetwork(request, callback)
            } catch (e: Exception) {
                registered = false
                owner?.cancel()
                owner = null
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        synchronized(callbackLock) {
            handoverJob?.cancel()
            handoverJob = null
            owner?.cancel()
            owner = null
            upstream = UpstreamState()
            if (!registered) return
            registered = false
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
            }
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = owner?.launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                synchronized(callbackLock) {
                    if (registered && upstream.current == network) onHandover(this@NetworkMonitor)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
