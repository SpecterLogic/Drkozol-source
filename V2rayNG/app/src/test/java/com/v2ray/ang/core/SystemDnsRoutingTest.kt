package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SystemDnsRoutingTest {
    @Test fun lanProxyOptInOverridesOrdinaryPrivateDirectRuleAndKeepsBalancerTarget() {
        val config = config()
        val ordinary = V2rayConfig.RoutingBean.RulesBean(ip = listOf("geoip:private"), outboundTag = "direct")
        config.routing.rules.add(ordinary)
        CoreConfigManager.applyLanProxyRouting(config, false, null)
        assertEquals(listOf(ordinary), config.routing.rules)
        CoreConfigManager.applyLanProxyRouting(config, true, "balanced")
        val lan = config.routing.rules.first()
        assertNull(lan.outboundTag)
        assertEquals("balanced", lan.balancerTag)
        assertTrue(lan.ip.orEmpty().contains("192.168.0.0/16"))
        assertFalse(lan.ip.orEmpty().contains("127.0.0.0/8"))
        assertFalse(lan.ip.orEmpty().contains("169.254.0.0/16"))
        assertSame(ordinary, config.routing.rules.last())
        CoreConfigManager.applySystemDnsInterception(config, true, "tun")
        assertEquals("dns-out", config.routing.rules.first().outboundTag)
    }

    @Test fun lanProxyWithoutBalancerTargetsTheOrdinaryProxy() {
        val config = config()
        CoreConfigManager.applyLanProxyRouting(config, true, null)
        assertEquals(AppConfig.TAG_PROXY, config.routing.rules.single().outboundTag)
        assertNull(config.routing.rules.single().balancerTag)
    }
    private fun config() = V2rayConfig(log = V2rayConfig.LogBean(), inbounds = arrayListOf(),
        outbounds = arrayListOf(), routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf()),
        dns = V2rayConfig.DnsBean(servers = ArrayList<Any>(AppConfig.DNS_PROXY.split(","))))

    @Test fun defaultCapturesAndroidDnsBeforeOrdinaryRoutes() {
        val config = config()
        val ordinary = V2rayConfig.RoutingBean.RulesBean(domain = listOf("example.com"), outboundTag = "proxy")
        config.routing.rules.add(ordinary)
        CoreConfigManager.applySystemDnsInterception(config, AppConfig.DEFAULT_LOCAL_DNS_ENABLED, "tun")
        assertEquals("53", config.routing.rules.first().port)
        assertEquals(listOf("tun"), config.routing.rules.first().inboundTag)
        assertEquals("dns-out", config.routing.rules.first().outboundTag)
        assertSame(ordinary, config.routing.rules.last())
        assertEquals(AppConfig.DNS_PROXY.split(","), config.dns?.servers)
        assertEquals("dns", config.outbounds.single().protocol)
    }
    @Test fun explicitDisabledChoicePreservesTheOldRouting() {
        val config = config()
        CoreConfigManager.applySystemDnsInterception(config, false, "tun")
        assertTrue(config.routing.rules.isEmpty())
        assertTrue(config.outbounds.isEmpty())
    }
    @Test fun hevUsesSocksAndRepeatedApplicationDoesNotDuplicateDns() {
        val config = config()
        repeat(2) { CoreConfigManager.applySystemDnsInterception(config, true, "socks") }
        assertEquals(listOf("socks"), config.routing.rules.single().inboundTag)
        assertEquals(1, config.outbounds.size)
    }
    @Test fun proxyOnlyDoesNotHijackUnrelatedInboundTraffic() {
        val config = config()
        CoreConfigManager.applySystemDnsInterception(config, true, null)
        assertTrue(config.routing.rules.isEmpty())
        assertEquals("dns-out", config.outbounds.single().tag)
    }
}
