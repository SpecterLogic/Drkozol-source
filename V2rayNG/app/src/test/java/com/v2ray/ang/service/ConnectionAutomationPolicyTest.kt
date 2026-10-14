package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionAutomationPolicyTest {
    @Test fun wakeWithoutASecureLockStillResumesButALockedScreenDoesNot() {
        assertTrue(isUnlockedScreen(true, false))
        assertFalse(isUnlockedScreen(false, false))
        assertFalse(isUnlockedScreen(true, true))
        assertFalse(isUnlockedScreen(false, true))
    }

    @Test fun repeatedOwnedPauseAndManualStopRemainDistinct() {
        assertTrue(isAutomationPause("pause-a", "pause-a"))
        assertTrue(isAutomationPause("pause-a", "pause-a"))
        assertFalse(isAutomationPause("", "pause-a"))
        assertFalse(isAutomationPause(null, "pause-a"))
        assertFalse(isAutomationPause("pause-b", "pause-a"))
        assertFalse(isAutomationPause("", ""))
    }

    @Test fun unlockResumesOnlyTheSameExplicitChoiceWithPermissionAndOptIn() {
        assertTrue(shouldResumeOnUnlock("a", "a", true, true))
        assertFalse(shouldResumeOnUnlock("a", "b", true, true))
        assertFalse(shouldResumeOnUnlock(null, "a", true, true))
        assertFalse(shouldResumeOnUnlock("", "", true, true))
        assertFalse(shouldResumeOnUnlock("a", "a", false, true))
        assertFalse(shouldResumeOnUnlock("a", "a", true, false))
    }

    @Test fun dnsReplacementPreservesAllPeerAndObfuscationFields() {
        val source = "[Interface]\nPrivateKey = fixture\nDNS = 8.8.8.8\nJc = 4\n[Peer]\nPublicKey = fixture-peer\nEndpoint = example.test:1234\nAllowedIPs = 0.0.0.0/0\n"
        val result = amneziaDnsConfig(source, listOf("1.1.1.1", "9.9.9.9"))
        assertTrue(result.contains("DNS = 1.1.1.1, 9.9.9.9"))
        assertFalse(result.contains("DNS = 8.8.8.8"))
        assertTrue(result.contains("Jc = 4"))
        assertEquals(source.substringAfter("[Peer]"), result.substringAfter("[Peer]"))
        assertEquals(source, amneziaDnsConfig(source, emptyList()))
        assertEquals("invalid", amneziaDnsConfig("invalid", listOf("1.1.1.1")))
    }
}
