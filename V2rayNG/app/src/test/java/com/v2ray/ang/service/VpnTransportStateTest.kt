package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VpnTransportStateTest {
    @Test fun duplicateCallbacksAndOldNetworkLossCannotHideAnActiveTunnel() {
        val snapshot = VpnNetworkSnapshot<String>()
        assertFalse(snapshot.active())
        snapshot.available("old")
        snapshot.available("old")
        assertTrue(snapshot.active())
        snapshot.available("new")
        snapshot.lost("old")
        snapshot.lost("old")
        snapshot.lost("missing")
        assertTrue(snapshot.active())
        snapshot.lost("new")
        assertFalse(snapshot.active())
    }
}
