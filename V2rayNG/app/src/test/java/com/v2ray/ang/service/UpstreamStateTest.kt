package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpstreamStateTest {
    @Test fun firstNetworkDoesNotReloadAndDuplicateIsIgnored() {
        val state = UpstreamState<String>()
        assertNull(state.current)
        assertFalse(state.available("wifi"))
        assertFalse(state.available("wifi"))
    }

    @Test fun oldNetworkLossCannotClearReplacement() {
        val state = UpstreamState<String>()
        state.available("cellular")
        assertTrue(state.available("wifi"))
        assertFalse(state.lost("cellular"))
        assertEquals("wifi", state.current)
    }

    @Test fun returningAfterOutageReloadsEvenTheSameNetwork() {
        val state = UpstreamState<String>()
        state.available("wifi")
        assertTrue(state.lost("wifi"))
        assertNull(state.current)
        assertFalse(state.lost("wifi"))
        assertTrue(state.available("wifi"))
    }
}
