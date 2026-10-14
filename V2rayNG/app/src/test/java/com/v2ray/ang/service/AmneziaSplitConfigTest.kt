package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AmneziaSplitConfigTest {
    private val raw = "[Interface]\nPrivateKey = secret\nS3 = 40\nIncludedApplications = old.app\n[Peer]\nEndpoint = host:443\n"
    @Test fun disabledOrEmptyPreservesSubscription() {
        assertEquals(raw, amneziaSplitConfig(raw, false, true, setOf("test.app")))
        assertEquals(raw, amneziaSplitConfig(raw, true, true, emptySet()))
    }
    @Test fun exclusionOverridesAllowlistWithoutChangingTunnelFields() {
        val config = amneziaSplitConfig(raw, true, true, setOf("z.app", "a.app"))
        assertTrue(config.contains("ExcludedApplications = a.app, z.app"))
        assertFalse(config.contains("IncludedApplications"))
        assertTrue(config.contains("PrivateKey = secret\nS3 = 40"))
        assertTrue(config.contains("[Peer]\nEndpoint = host:443"))
    }
    @Test fun inclusionRemovesPreviousExclusions() {
        val config = amneziaSplitConfig(raw.replace("IncludedApplications", "ExcludedApplications"), true, false, setOf("test.app"))
        assertTrue(config.contains("IncludedApplications = test.app"))
        assertFalse(config.contains("ExcludedApplications"))
    }
    @Test fun commentedInterfaceHeadersStillApplyExclusions() {
        val commented = raw.replace("[Interface]", "[Interface] # subscription")
        val config = amneziaSplitConfig(commented, true, true, setOf("test.app"))
        assertTrue(config.contains("[Interface] # subscription\nExcludedApplications = test.app"))
        assertFalse(config.contains("IncludedApplications"))
        assertTrue(config.contains("S3 = 40"))
    }
}
