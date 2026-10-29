package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Applies only to automatic starts; a deliberate manual connection always remains available. */
object AutomaticNetworkPolicy {
    const val KEY = "dr_automatic_network_policy"
    val choices = setOf("any", "wifi", "mobile", "unmetered")

    fun allows(policy: String, connected: Boolean, wifi: Boolean, mobile: Boolean, unmetered: Boolean): Boolean =
        when (policy) {
            "any" -> true // Preserve existing startup behavior for older settings.
            "wifi" -> connected && wifi
            "mobile" -> connected && mobile
            "unmetered" -> connected && unmetered
            else -> false
        }

    fun allows(context: Context): Boolean {
        val policy = MmkvManager.decodeSettingsString(KEY) ?: "any"
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        return allows(policy, capabilities != null,
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
    }
}
