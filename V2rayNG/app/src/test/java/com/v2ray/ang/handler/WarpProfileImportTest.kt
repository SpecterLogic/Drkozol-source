package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class WarpProfileImportTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val config get() = "[Interface]\nPrivateKey = $key\nAddress = 172.16.0.2/32, 2606:4700:110::2/128\n[Peer]\nPublicKey = $key\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = [2001:db8::1]:2408"
    @Test fun preservesIpv6EndpointAndDoesNotInventAccountCredentials() {
        val profile = WarpProfileImport.parse(config)!!
        assertEquals("2001:db8::1", profile.server)
        assertEquals("2408", profile.serverPort)
        assertEquals(key, profile.secretKey)
        assertEquals("WARP", profile.remarks)
    }
    @Test fun rejectsHooksAndInvalidOrMultiplePeers() {
        assertNull(WarpProfileImport.parse(config + "\nPostUp = anything"))
        assertNull(WarpProfileImport.parse(config + "\n[Peer]\nPublicKey = $key"))
        assertNull(WarpProfileImport.parse(config.replace(key, "invalid")))
        assertNull(WarpProfileImport.parse(config.replace(":2408", ":0")))
    }
}
