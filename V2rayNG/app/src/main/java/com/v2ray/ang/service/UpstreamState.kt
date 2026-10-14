package com.v2ray.ang.service

/** Tracks the chosen transport, ignoring late callbacks from a replaced network. */
internal class UpstreamState<T> {
    var current: T? = null
        private set
    private var seenNetwork = false

    fun available(network: T): Boolean {
        val reload = seenNetwork && current != network
        current = network
        seenNetwork = true
        return reload
    }

    fun lost(network: T): Boolean {
        if (current != network) return false
        current = null
        return true
    }
}
