package com.v2ray.ang.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrafficConfigurationTest {
    @Test fun countersOverrideDisabledFieldsAndPreserveCustomPolicy() {
        val json = JsonParser.parseString("""{"policy":{"levels":{"8":{"connIdle":300}},"system":{"statsOutboundUplink":false,"statsOutboundDownlink":false,"statsInboundUplink":true}}}""").asJsonObject
        CoreConfigManager.configureTrafficStatistics(json)
        val policy = json.getAsJsonObject("policy")
        assertEquals(300, policy.getAsJsonObject("levels").getAsJsonObject("8").get("connIdle").asInt)
        val system = policy.getAsJsonObject("system")
        assertTrue(system.get("statsInboundUplink").asBoolean)
        assertTrue(system.get("statsOutboundUplink").asBoolean)
        assertTrue(system.get("statsOutboundDownlink").asBoolean)
        assertTrue(json.get("stats").isJsonObject)
    }
    @Test fun missingPolicyGetsBidirectionalCounters() {
        val json = JsonParser.parseString("{}").asJsonObject
        CoreConfigManager.configureTrafficStatistics(json)
        val system = json.getAsJsonObject("policy").getAsJsonObject("system")
        assertTrue(system.get("statsOutboundUplink").asBoolean)
        assertTrue(system.get("statsOutboundDownlink").asBoolean)
    }
}
