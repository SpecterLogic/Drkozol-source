package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.AppConfig
import java.util.Locale

/** Some subscription panels encode account limits as a local SOCKS row, not a proxy endpoint. */
internal object ProfileEligibility {
    private val accountLabel = Regex("(?i)(?<![a-z])\\p{Nd}+(?:[.,]\\p{Nd}+)?\\s*[KMGT]i?B\\b|(?:⌛|⏳)\\s*\\p{Nd}+|\\p{Nd}+\\s*(?:days?|روز)(?!\\p{L})")

    fun isAccountInfo(profile: ProfileItem): Boolean {
        // Manual local proxies remain selectable, including names containing quota/day text.
        if (profile.subscriptionId.isNullOrBlank() || profile.subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID ||
            profile.configType != EConfigType.SOCKS || !accountLabel.containsMatchIn(profile.remarks.orEmpty())) return false
        val host = profile.server.orEmpty().trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val parts = host.split('.')
        val loopback = host in setOf("localhost", "::1", "::", "0.0.0.0", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0") ||
            (parts.size == 4 && parts.first() == "127" && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true })
        return loopback || profile.serverPort?.toIntOrNull() == 0
    }

    fun selectedGuid(currentGuid: String?, currentProfile: ProfileItem?, profiles: Map<String, ProfileItem>): String? {
        val selected = currentProfile ?: currentGuid?.let(profiles::get)
        return currentGuid?.takeIf { it.isNotBlank() && selected != null && !isAccountInfo(selected) }
    }
}
