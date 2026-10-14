package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerProtocolLabelsTest {
    @Test fun tcpAndRawAliasesHaveAnExplicitTcpTransport() {
        val profile = ProfileItem(configType = EConfigType.VLESS)
        assertEquals("VLESS / TCP", serverProtocolDescription(profile))
        profile.network = "tcp"
        assertEquals("VLESS / tcp", serverProtocolDescription(profile))
        profile.network = "raw"
        assertEquals("VLESS / TCP", serverProtocolDescription(profile))
        profile.network = "xhttp"; profile.security = "tls"
        assertEquals("VLESS / xhttp / tls", serverProtocolDescription(profile))
    }
    @Test fun wireguardAndAmneziaAreDisplayedAsUdp() {
        assertEquals("WIREGUARD / UDP", serverProtocolDescription(ProfileItem(configType = EConfigType.WIREGUARD)))
        val profile = ProfileItem(configType = EConfigType.CUSTOM).apply { amneziaConfig = "[Interface]" }
        assertEquals("AmneziaWG / UDP", serverProtocolDescription(profile))
    }
}
