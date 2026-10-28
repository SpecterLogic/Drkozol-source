package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoutingProfileLinkTest {
    @Test fun linkRoundTripRetainsAllRoutingActionsAndAdvancedConditions() {
        val original = listOf(
            RulesetItem(id = "old", domain = listOf("domain:example.org"), outboundTag = "direct", locked = true),
            RulesetItem(ip = listOf("192.0.2.0/24"), outboundTag = "proxy"),
            RulesetItem(domain = listOf("domain:blocked.example"), outboundTag = "block", port = "443", enabled = false)
        )
        val decoded = RoutingProfileLink.parse(RoutingProfileLink.export(original))!!
        assertEquals(listOf("direct", "proxy", "block"), decoded.map { it.outboundTag })
        assertEquals(original.map { it.domain }, decoded.map { it.domain })
        assertEquals(original.map { it.ip }, decoded.map { it.ip })
        assertEquals("443", decoded.last().port)
        assertFalse(decoded.last().enabled)
        assertTrue(decoded.all { it.locked == false && it.id.isNotEmpty() })
        assertNotEquals("old", decoded.first().id)
    }

    @Test fun precedingJsonExportRemainsReadable() {
        assertEquals("direct", RoutingProfileLink.parse("""[{"domain":["domain:example.org"],"outboundTag":"direct"}]""")!!.single().outboundTag)
    }

    @Test fun malformedEmptyAndOversizedProfilesAreRejected() {
        for (value in listOf("", "[]", "{}", "null", "[null]", "drkozol://routing/add/!", "x".repeat(1_000_001),
            """[{"outboundTag":"proxy"}]""", """[{"domain":[""],"outboundTag":"block"}]""")) {
            assertNull(RoutingProfileLink.parse(value))
        }
    }
}
