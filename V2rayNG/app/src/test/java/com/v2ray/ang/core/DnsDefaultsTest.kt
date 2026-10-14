package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DnsDefaultsTest {
    @Test fun previousReleaseDefaultGetsAnIndependentFallback() {
        val migrated = SettingsManager.remoteDnsDefaultForUpgrade("https://cloudflare-dns.com/dns-query")
        assertEquals(listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"), migrated.split(","))
    }
    @Test fun absentAndEmptyValuesUseTheSameResolversAsFreshInstall() {
        listOf(null, "", " ").forEach { assertEquals(AppConfig.DNS_PROXY, SettingsManager.remoteDnsDefaultForUpgrade(it)) }
    }
    @Test fun customResolversAreNotOverwritten() {
        val custom = "https://resolver.example/dns-query,9.9.9.9"
        assertEquals(custom, SettingsManager.remoteDnsDefaultForUpgrade(custom))
    }
    @Test fun bootstrapPreventsRecursiveLookupButRetainsCustomHosts() {
        val original: Map<String, Any> = mapOf("cloudflare-dns.com" to "203.0.113.5", "example.com" to "192.0.2.4")
        val result = CoreConfigManager.bootstrapDnsHosts(original, AppConfig.DNS_PROXY.split(","))
        assertEquals("203.0.113.5", result["cloudflare-dns.com"])
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), result["dns.google"])
        assertEquals("192.0.2.4", result["example.com"])
        assertFalse(original.containsKey("dns.google"))
    }
    @Test fun unrelatedOrMalformedResolversDoNotInjectStockHosts() {
        assertTrue(CoreConfigManager.bootstrapDnsHosts(emptyMap(), listOf("https://resolver.example/dns-query", "not a URI")).isEmpty())
    }
}
