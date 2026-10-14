package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.AppConfig
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileEligibilityTest {
    @Test fun roundRobinFallbackPrefersRealHealthyPeersAndRetainsRecoveryCandidates() {
        val delays = linkedMapOf("failed" to -1L, "unknown" to null, "untested" to 0L,
            "slow" to 450L, "fast-a" to 80L, "fast-b" to 80L)
        assertEquals(listOf("fast-a", "fast-b", "slow", "unknown", "untested", "failed"),
            delays.keys.sortedBy { balancerFallbackRank(delays[it]) })
        assertEquals(emptyList<String>(), emptyList<String>().sortedBy { balancerFallbackRank(null) })
    }

    private fun socks(name: String, host: String = "127.0.0.1", port: String = "443", group: String = "subscription") =
        ProfileItem(configType = EConfigType.SOCKS, subscriptionId = group, remarks = name, server = host, serverPort = port)

    @Test
    fun onlyPanelInformationWithLocalOrDummyEndpointIsExcluded() {
        assertTrue(ProfileEligibility.isAccountInfo(socks("📊10.00GB|⌛29روز")))
        assertTrue(ProfileEligibility.isAccountInfo(socks("29 days", "localhost")))
        assertTrue(ProfileEligibility.isAccountInfo(socks("10 GB", "::1")))
        assertTrue(ProfileEligibility.isAccountInfo(socks("10 GB", "0.0.0.0")))
        assertTrue(ProfileEligibility.isAccountInfo(socks("10 GB", "example.org", "0")))
        assertFalse(ProfileEligibility.isAccountInfo(socks("10 GB", "proxy.example.org")))
        assertFalse(ProfileEligibility.isAccountInfo(socks("Local SOCKS backup")))
        assertFalse(ProfileEligibility.isAccountInfo(socks("10 GB", group = "")))
        assertFalse(ProfileEligibility.isAccountInfo(socks("10 GB", group = AppConfig.DEFAULT_SUBSCRIPTION_ID)))
        assertFalse(ProfileEligibility.isAccountInfo(socks("10 GB", "127.proxy.example.org")))
        assertFalse(ProfileEligibility.isAccountInfo(ProfileItem(configType = EConfigType.VLESS, subscriptionId = "subscription", remarks = "10 GB", server = "127.0.0.1")))
    }

    @Test
    fun importStaysUnselectedAndExplicitValidSelectionSurvives() {
        val info = socks("10GB|⌛29روز")
        val vless = ProfileItem(configType = EConfigType.VLESS, remarks = "Server", server = "proxy.example.org")
        val local = socks("My local SOCKS", group = "")
        val profiles = linkedMapOf("info" to info, "vless" to vless, "local" to local)
        assertNull(ProfileEligibility.selectedGuid(null, null, profiles))
        assertNull(ProfileEligibility.selectedGuid("", null, profiles))
        assertNull(ProfileEligibility.selectedGuid("info", info, profiles))
        assertEquals("vless", ProfileEligibility.selectedGuid("vless", null, profiles))
        assertEquals("local", ProfileEligibility.selectedGuid("local", local, profiles))
        assertEquals("outside", ProfileEligibility.selectedGuid("outside", vless, profiles))
        assertNull(ProfileEligibility.selectedGuid(null, null, mapOf("info" to info)))
        assertNull(ProfileEligibility.selectedGuid("missing", null, emptyMap()))
    }

    @Test
    fun importReplacementNeverChoosesTheMetadataRow() {
        val profiles = linkedMapOf("info" to socks("10GB|⌛29روز"),
            "server" to ProfileItem(configType = EConfigType.VLESS, remarks = "Server", server = "proxy.example.org"))
        val eligible = profiles.filterValues { !ProfileEligibility.isAccountInfo(it) }
        assertNull(ProfileReplacement.findSelectedReplacement(eligible, null, null))
        assertNull(ProfileReplacement.findSelectedReplacement(emptyMap(), null, null))
    }

    @Test
    fun previouslyReleasedProfileJsonDistinguishesInfoFromManualAndVless() {
        val gson = Gson()
        val info = gson.fromJson("""{"configType":"SOCKS","subscriptionId":"subscription","remarks":"10 GB | 29 days","server":"127.0.0.1","serverPort":"443"}""", ProfileItem::class.java)
        val manual = gson.fromJson("""{"configType":"SOCKS","subscriptionId":"","remarks":"10 GB | 29 days","server":"127.0.0.1","serverPort":"1080"}""", ProfileItem::class.java)
        val vless = gson.fromJson("""{"configType":"VLESS","subscriptionId":"subscription","remarks":"10 GB | 29 days","server":"127.0.0.1","serverPort":"443"}""", ProfileItem::class.java)
        assertTrue(ProfileEligibility.isAccountInfo(info))
        assertFalse(ProfileEligibility.isAccountInfo(manual))
        assertFalse(ProfileEligibility.isAccountInfo(vless))
        assertFalse(ProfileEligibility.isAccountInfo(gson.fromJson("""{"configType":"SOCKS","server":"127.0.0.1","serverPort":"443"}""", ProfileItem::class.java)))
    }
}
