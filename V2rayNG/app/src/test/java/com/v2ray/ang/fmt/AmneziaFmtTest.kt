package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class AmneziaFmtTest {
    private val configuration = """
        [Interface]
        PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=
        Address = 10.0.0.2/32
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 0
        S2 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        [Peer]
        PublicKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 192.0.2.1:51820
    """.trimIndent()
    @Test fun `imports AWG obfuscation values without dropping them`() {
        val profile = AmneziaFmt.parse(configuration)!!
        assertEquals(configuration, profile.amneziaConfig)
    }
    @Test fun `imports a subscription share link and name`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(configuration.toByteArray())
        val profile = AmneziaFmt.parse("awg://$payload#My%20server")!!
        assertEquals("My server", profile.remarks)
        assertEquals(configuration, profile.amneziaConfig)
    }
    @Test fun `rejects empty invalid and script configurations`() {
        assertNull(AmneziaFmt.parse(""))
        assertNull(AmneziaFmt.parse("awg://bad"))
        assertNull(AmneziaFmt.parse(configuration.replace("Address =", "PreUp = echo hi\nAddress =")))
        assertNull(AmneziaFmt.parse("[Interface]\nPrivateKey = invalid"))
    }
    @Test fun `imports panel vpn links containing a base64 configuration`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(configuration.toByteArray())
        assertEquals(configuration, AmneziaFmt.parse("vpn://$payload")!!.amneziaConfig)
    }
    @Test fun `preserves AWG 3 header protection and timing options`() {
        val extended = configuration.replace("[Peer]", """
            HeaderProtectionKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=
            ContentPaddingAddition = 0-64
            RekeyAfterTime = 100-140
            RekeyTimeout = 5
            RejectAfterTime = 180
            KeepaliveTimeout = 10
            MaxHandshakeAttempts = 18
            RandomTrailers = on
            DisableCookies = off
            [Peer]
        """.trimIndent())
        val parsed = AmneziaFmt.parse(extended)!!
        assertEquals(extended, parsed.amneziaConfig)
        val nativeConfig = org.amnezia.awg.config.Config.parse(extended.byteInputStream()).toAwgUserspaceString()
        assertTrue(nativeConfig.contains("header_protection_key="))
        assertTrue(nativeConfig.contains("random_trailers="))
    }
}
