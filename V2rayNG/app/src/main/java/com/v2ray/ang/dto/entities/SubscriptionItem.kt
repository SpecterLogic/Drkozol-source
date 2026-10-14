package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = true,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,
    var usage: SubscriptionUsage? = null,
    var renewalUrl: String? = null,
    var announcement: String? = null,
    var showOnHome: Boolean = true,
    var customUpdateInterval: Boolean = true,
    var delayTestUrl: String? = null,
    var tunnelDns: String? = null,
    var displayDelayAs: String = "time",
    var serverListOrder: String = "ping",
    var connectionStyle: String = "classic",
    var customRemarks: Boolean = false,
    var probeMethod: String = "real",
)

