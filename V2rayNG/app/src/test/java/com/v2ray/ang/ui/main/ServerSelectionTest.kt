package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ServerSelectionTest {
    @Test fun loginAcceptsHttpsAndRejectsEmptyMalformedAndCredentials() {
        assertTrue(validSubscriptionUrl(" https://panel.example:2096/sub/test "))
        listOf("", " ", "https://", "http://panel.example/sub", "vless://test", "https://user:pass@panel.example/sub", "https://bad host/sub").forEach {
            assertFalse(validSubscriptionUrl(it), it)
        }
    }
    @Test fun realDelayWinsOverLowerTcpAndIgnoresOtherGroups() {
        assertEquals("a", fastestServerGuid(setOf("a", "b"), mapOf("a" to 120, "other" to 1), mapOf("b" to 10)))
    }
    @Test fun tcpFallbackSkipsFailedZeroAndMissingResults() {
        assertEquals("b", fastestServerGuid(setOf("a", "b"), mapOf("a" to -1), mapOf("a" to 0, "b" to 25)))
        assertNull(fastestServerGuid(setOf("a"), emptyMap(), mapOf("a" to -2)))
        assertNull(fastestServerGuid(emptySet(), mapOf("a" to 2), emptyMap()))
    }
    @Test fun reachableTcpPortCannotOverrideFailedRealConnection() {
        assertEquals("b", fastestServerGuid(setOf("a", "b"), mapOf("a" to -1), mapOf("a" to 1, "b" to 25)))
        assertNull(fastestServerGuid(setOf("a"), mapOf("a" to -1), mapOf("a" to 1)))
        assertNull(fastestServerGuid(setOf("a"), mapOf("a" to -2), mapOf("a" to 1)))
        assertEquals("a", fastestServerGuid(setOf("a"), mapOf("a" to 0), mapOf("a" to 25)))
    }
    @Test fun bothProbeKindsKeepIndependentResults() {
        val initial = MainUiState()
        assertTrue(initial.tcpResults.isEmpty())
        assertTrue(initial.realResults.isEmpty())
        assertFalse(initial.subscriptionReady)
        val state = initial.withProbeResult("a", 42, ProbeKind.TCP).withProbeResult("a", 240, ProbeKind.REAL)
        assertEquals(42L, state.tcpResults["a"])
        assertEquals(240L, state.realResults["a"])
        val reset = state.resetProbeResults(setOf("a"), ProbeKind.REAL)
        assertNull(reset.realResults["a"])
        assertEquals(42L, reset.tcpResults["a"])
        assertEquals(state, state.resetProbeResults(emptySet(), ProbeKind.TCP))
    }
    @Test fun awgHasItsOwnCategoryAndOtherProtocolsKeepTheirCategory() {
        val vless = ProfileItem(configType = EConfigType.VLESS)
        assertEquals(ServerCategory.VLESS, serverCategory(vless))
        assertEquals(ServerCategory.AMNEZIA, serverCategory(vless.copy(amneziaConfig = "[Interface]")))
        assertEquals(ServerCategory.SHADOWSOCKS, serverCategory(ProfileItem(configType = EConfigType.SHADOWSOCKS)))
        assertEquals(ServerCategory.OTHER, serverCategory(ProfileItem(configType = EConfigType.SOCKS)))
    }
}
