package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.util.Base64
import org.amnezia.awg.config.Config

/** Explicit awg:// URL-safe Base64 .conf format, also accepted inside a subscription. */
object AmneziaFmt {
    fun parse(value: String): ProfileItem? = runCatching {
        val isLink = value.startsWith("awg://") || value.startsWith("vpn://")
        val raw = if (isLink) {
            val payload = value.substringAfter("://").substringBefore('#')
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        } else value
        require(raw.trimStart().startsWith("[Interface]"))
        require(!Regex("(?im)^\\s*(PreUp|PostUp|PreDown|PostDown)\\s*=").containsMatchIn(raw))
        Config.parse(raw.byteInputStream())
        ProfileItem(configType = EConfigType.CUSTOM).apply {
            remarks = if (isLink && '#' in value) {
                java.net.URLDecoder.decode(value.substringAfter('#'), "UTF-8")
            } else "AmneziaWG"
            amneziaConfig = raw
            description = "AmneziaWG / UDP"
        }
    }.getOrNull()
}
