package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreSocketRecoveryTest {
    @Test fun tcpPeersReceiveFiniteRecoveryOptionsWithTheirExistingRoutingIntact() {
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean(network = "tcp"))
        CoreOutboundBuilder.applyMobileTcpRecovery(outbound)
        val options = outbound.streamSettings!!.sockopt!!
        assertEquals(30, options.tcpKeepAliveIdle)
        assertEquals(10, options.tcpKeepAliveInterval)
        assertEquals(30_000, options.tcpUserTimeout)
        assertTrue(Gson().toJson(outbound).contains("\"tcpUserTimeout\":30000"))
    }

    @Test fun releasedSocketJsonKeepsExplicitDisabledAndCustomValues() {
        val options = Gson().fromJson("""{"tcpKeepAliveIdle":-1,"dialerProxy":"chain","mark":42}""", OutboundBean.StreamSettingsBean.SockoptBean::class.java)
        assertNull(options.tcpKeepAliveInterval)
        assertNull(options.tcpUserTimeout)
        options.tcpKeepAliveInterval = 0
        options.tcpUserTimeout = 90_000
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean(sockopt = options))
        CoreOutboundBuilder.applyMobileTcpRecovery(outbound)
        assertSame(options, outbound.streamSettings!!.sockopt)
        assertEquals(-1, options.tcpKeepAliveIdle)
        assertEquals(0, options.tcpKeepAliveInterval)
        assertEquals(90_000, options.tcpUserTimeout)
        assertEquals("chain", options.dialerProxy)
        assertEquals(42, options.mark)
    }

    @Test fun nativeUdpAndMissingStreamsAreUntouched() {
        for (protocol in listOf("wireguard", "hysteria2", "freedom", "blackhole")) {
            val outbound = OutboundBean(protocol = protocol, streamSettings = OutboundBean.StreamSettingsBean())
            CoreOutboundBuilder.applyMobileTcpRecovery(outbound)
            assertNull(outbound.streamSettings!!.sockopt)
        }
        val udp = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean(network = "kcp"))
        CoreOutboundBuilder.applyMobileTcpRecovery(udp)
        assertNull(udp.streamSettings!!.sockopt)
        CoreOutboundBuilder.applyMobileTcpRecovery(OutboundBean(protocol = "vless", streamSettings = null))
    }
}
