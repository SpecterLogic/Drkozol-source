package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClientPreferencesTest {
    @Test fun twelveHourGlobalUpgradePreservesExplicitOtherAndProviderIntervals() {
        assertEquals(720L, ClientPreferences().updateMinutes)
        assertEquals(720L, refreshIntervalUpgrade(null))
        assertEquals(720L, refreshIntervalUpgrade("120"))
        listOf("30", "60", "360", "720", "1440", "invalid").forEach { assertNull(refreshIntervalUpgrade(it)) }
        assertEquals(720L, effectiveUpdateMinutes(false, 120, ClientPreferences().updateMinutes))
        assertEquals(120L, effectiveUpdateMinutes(true, 120, ClientPreferences().updateMinutes))
    }

    @Test fun automaticNativeProbesRequireQuietChargingTimeAndNoTunnel() {
        for (interactive in listOf(false, true)) for (charging in listOf(false, true)) for (vpn in listOf(false, true)) {
            assertEquals(!interactive && charging && !vpn, canRunAutomaticProbe(interactive, charging, vpn))
        }
    }

    @Test fun precedingUntypedDelayDoesNotBecomeARealOrTcpMeasurement() {
        val old = Gson().fromJson("""{"testDelayMillis":42}""", com.v2ray.ang.dto.entities.ServerAffiliationInfo::class.java)
        assertEquals(42L, old.testDelayMillis)
        assertNull(old.realDelayMillis)
        assertNull(old.tcpDelayMillis)
        old.realDelayMillis = 310
        old.tcpDelayMillis = -2
        assertEquals(old, Gson().fromJson(Gson().toJson(old), com.v2ray.ang.dto.entities.ServerAffiliationInfo::class.java))
    }
    @Test fun precedingSubscriptionFormatRetainsExplicitDisabledStateAndInterval() {
        val old = Gson().fromJson("""{"remarks":"Legacy","url":"https://legacy.example/sub","enabled":true,"autoUpdate":false,"updateInterval":720,"lastUpdated":1234}""", SubscriptionItem::class.java)
        assertFalse(old.autoUpdate)
        assertEquals(720L, old.updateInterval)
        assertEquals(1234L, old.lastUpdated)
        assertTrue(old.showOnHome)
        assertTrue(old.customUpdateInterval)
        assertEquals("real", old.probeMethod)
        assertNull(old.tunnelDns)
        assertEquals(old, Gson().fromJson(Gson().toJson(old), SubscriptionItem::class.java))
    }

    @Test fun intervalsUseGeneralOrCustomPolicyAndCannotOverflow() {
        assertEquals(120L, effectiveUpdateMinutes(false, 720, 120))
        assertEquals(720L, effectiveUpdateMinutes(true, 720, 120))
        assertEquals(15L, effectiveUpdateMinutes(true, -1, 120))
        assertEquals(43200L, effectiveUpdateMinutes(true, Long.MAX_VALUE, 120))
    }

    @Test fun urlAndDnsValidationRejectCredentialsAndMalformedValues() {
        assertTrue(validProbeUrl("https://example.test/check"))
        listOf("", "http://example.test", "https://u:p@example.test/check", "https://bad host", "vless://fixture").forEach { assertFalse(validProbeUrl(it), it) }
        listOf("", "1.1.1.1, 8.8.8.8", "2001:4860:4860::8888", "https://dns.example/dns-query").forEach { assertTrue(validDnsOverride(it), it) }
        listOf("999.1.1.1", "hostname", "1.1.1.1, bad", "https://user:pass@dns.example/dns-query").forEach { assertFalse(validDnsOverride(it), it) }
    }

    @Test fun balancerRejectsNativePeersComplexProfilesAndEmptyEndpoints() {
        val vless = ProfileItem(configType = EConfigType.VLESS, server = "node.example")
        assertTrue(balancerEligible(vless))
        assertFalse(balancerEligible(vless.copy(server = "")))
        assertFalse(balancerEligible(ProfileItem(configType = EConfigType.CUSTOM, amneziaConfig = "fixture", server = "node.example")))
        assertFalse(balancerEligible(ProfileItem(configType = EConfigType.POLICYGROUP, server = "node.example")))
    }

    @Test fun leaseExpiryOrMissingOwnerCannotLeaveUiPermanentlyBusy() {
        val lease = BackgroundProbeLease("fixture", 42, 100)
        assertTrue(lease.active(99, true))
        assertFalse(lease.active(100, true))
        assertFalse(lease.active(99, false))
        assertFalse(Gson().fromJson("{}", BackgroundProbeLease::class.java).active(0, false))
    }
}
