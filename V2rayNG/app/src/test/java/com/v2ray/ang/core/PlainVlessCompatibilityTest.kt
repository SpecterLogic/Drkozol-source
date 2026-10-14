package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlainVlessCompatibilityTest {
    @Test fun plainImportedAndLegacyProfilesRetainTheirRequestedConnectionMode() {
        val profile = ProfileItem(configType = EConfigType.VLESS).apply { method = "none"; security = null }
        assertTrue(CoreOutboundBuilder.preservesPlainVless(profile))
        profile.security = "none"
        assertTrue(CoreOutboundBuilder.preservesPlainVless(profile))
        profile.method = null
        assertTrue(CoreOutboundBuilder.preservesPlainVless(profile))
    }

    @Test fun tlsRealityEncryptedVlessAndOtherProtocolsNeverEnablePlainCompatibility() {
        val profile = ProfileItem(configType = EConfigType.VLESS).apply { method = "none" }
        listOf("tls", "reality", "invalid").forEach { security ->
            profile.security = security
            assertFalse(CoreOutboundBuilder.preservesPlainVless(profile))
        }
        profile.security = null; profile.method = "configured-encryption"
        assertFalse(CoreOutboundBuilder.preservesPlainVless(profile))
        assertFalse(CoreOutboundBuilder.preservesPlainVless(profile.copy(method = "none", configType = EConfigType.TROJAN)))
    }

    @Test fun previousReleasedOutboundJsonRetainsIdentityWithTheNewOptionAbsent() {
        val previous = """{"tag":"proxy","protocol":"vless","settings":{"address":"server.example","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}"""
        val outbound = Gson().fromJson(previous, V2rayConfig.OutboundBean::class.java)
        assertEquals("vless", outbound.protocol)
        assertEquals("00000000-0000-0000-0000-000000000001", outbound.settings?.id)
        assertNull(outbound.drkozolAllowPlainVless)
    }
}
