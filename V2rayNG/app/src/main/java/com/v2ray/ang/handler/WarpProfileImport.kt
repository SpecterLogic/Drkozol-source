package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.WireguardFmt
import java.net.URI
import java.util.Base64

/** Imports an existing WARP WireGuard profile; never enrolls or accepts terms for the user. */
object WarpProfileImport {
    fun parse(input: String): ProfileItem? = runCatching {
        require(input.length <= 65_536)
        require(input.trimStart().startsWith("[Interface]", true))
        require(Regex("(?im)^\\s*\\[Peer]\\s*$").findAll(input).count() == 1)
        require(!Regex("(?im)^\\s*(PreUp|PostUp|PreDown|PostDown|Jc|Jmin|Jmax|S1|S2|H1|H2|H3|H4)\\s*=").containsMatchIn(input))
        val endpointText = input.lineSequence().map { it.substringBefore('#').trim().split('=', limit = 2) }
            .first { it.size == 2 && it[0].trim().equals("Endpoint", true) }[1].trim()
        val endpoint = URI("udp://$endpointText")
        require(!endpoint.host.isNullOrBlank() && endpoint.port in 1..65535 && endpoint.userInfo == null)
        WireguardFmt.parseWireguardConfFile(input).apply {
            require(Base64.getDecoder().decode(secretKey).size == 32)
            require(Base64.getDecoder().decode(publicKey).size == 32)
            require(localAddress.orEmpty().split(',').all { it.trim().contains('/') })
            server = endpoint.host.removeSurrounding("[", "]")
            serverPort = endpoint.port.toString()
            remarks = "WARP"
        }
    }.getOrNull()
}
