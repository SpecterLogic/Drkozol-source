package com.v2ray.ang.service

/** Two failed probe rounds are required; recovery is rate limited to avoid reconnect loops. */
internal class ConnectionRecovery {
    private var failures = 0
    private var lastRecovery: Long? = null

    fun record(healthy: Boolean, elapsedMillis: Long, downloadRecentlyActive: Boolean = false): Boolean {
        // A failed probe endpoint must never tear down a video that is still receiving data.
        if (healthy || downloadRecentlyActive) { failures = 0; return false }
        failures = (failures + 1).coerceAtMost(2)
        if (failures < 2 || lastRecovery?.let { elapsedMillis - it < 60_000 } == true) return false
        lastRecovery = elapsedMillis
        failures = 0
        return true
    }
}
