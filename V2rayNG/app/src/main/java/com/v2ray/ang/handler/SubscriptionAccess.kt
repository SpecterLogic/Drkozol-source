package com.v2ray.ang.handler

/** Shared by daemon start/reload and the subscription dashboard. No UI-process running flag. */
object SubscriptionAccess {
    fun locked(guid: String?, now: Long = System.currentTimeMillis() / 1000): Boolean {
        if (guid.isNullOrEmpty()) return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        return subscriptionLocked(profile.subscriptionId, now)
    }

    fun subscriptionLocked(subscriptionId: String?, now: Long = System.currentTimeMillis() / 1000): Boolean {
        if (subscriptionId.isNullOrEmpty()) return false
        val subscription = MmkvManager.decodeSubscription(subscriptionId) ?: return true
        return !subscription.enabled || subscription.usage?.locked(now) == true
    }
}
