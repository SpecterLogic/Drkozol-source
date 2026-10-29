package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class HappRoutingParserTest {
    private fun link(json: String, mode: String = "onadd") = "happ://routing/$mode/" + Base64.getEncoder().encodeToString(json.toByteArray())

    @Test fun convertsRulesAndDnsWithoutDiscardingProfileScope() {
        val command = HappRoutingParser.parse(link("""{"Name":"Example","DirectSites":["geosite:ir"],"ProxyIp":["192.0.2.0/24"],"BlockSites":["domain:ads.invalid"],"GlobalProxy":"false","DomainStrategy":"IPOnDemand","DomesticDNSType":"DoU","DomesticDNSIP":"9.9.9.9","DnsHosts":{"dns.invalid":"192.0.2.1"}}"""))!!
        assertTrue(command.activate)
        assertFalse(command.profile!!.globalProxy)
        assertEquals(3, command.profile.rules.size)
        assertEquals("block", command.profile.rules.first().outboundTag)
        assertEquals("9.9.9.9", command.profile.domesticDns)
        assertEquals("192.0.2.1", command.profile.hosts["dns.invalid"])
        assertFalse(HappRoutingParser.parse(link("{}", "add"))!!.activate)
        assertTrue(HappRoutingParser.parse("happ://routing/off")!!.off)
    }

    @Test fun malformedAndUnsupportedPayloadsDoNotReplaceWorkingSettings() {
        for (input in listOf("happ://routing/add/!", link("[]"), link("""{"RemoteDNSDomain":"http://dns.invalid"}"""),
            link("""{"GlobalProxy":"maybe"}"""), link("""{"DomainStrategy":"unknown"}"""),
            link("""{"Geoipurl":"file:///private/geoip.dat"}"""))) assertNull(HappRoutingParser.parse(input))
    }
}
