package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionRecoveryTest {
    @Test fun oneFailureDoesNotDisconnectAndSuccessResetsTheCount() {
        val recovery = ConnectionRecovery()
        assertFalse(recovery.record(true, 0))
        assertFalse(recovery.record(false, 30_000))
        assertFalse(recovery.record(true, 60_000))
        assertFalse(recovery.record(false, 90_000))
        assertTrue(recovery.record(false, 120_000))
    }
    @Test fun persistentFailureCannotCreateARestartLoop() {
        val recovery = ConnectionRecovery()
        assertFalse(recovery.record(false, 0))
        assertTrue(recovery.record(false, 30_000))
        assertFalse(recovery.record(false, 60_000))
        assertFalse(recovery.record(false, 89_999))
        assertTrue(recovery.record(false, 90_000))
        assertFalse(recovery.record(false, 120_000))
        assertFalse(recovery.record(false, 149_999))
        assertTrue(recovery.record(false, 150_000))
    }

    @Test fun liveVideoMustNotBeRestartedWhenAProbeEndpointFails() {
        val recovery = ConnectionRecovery()
        assertFalse(recovery.record(false, 10_000))
        assertFalse(recovery.record(false, 20_000, downloadRecentlyActive = true))
        assertFalse(recovery.record(false, 30_000))
        assertFalse(recovery.record(false, 40_000, downloadRecentlyActive = true))
        assertFalse(recovery.record(false, 50_000))
        assertTrue(recovery.record(false, 60_000))
    }
}
