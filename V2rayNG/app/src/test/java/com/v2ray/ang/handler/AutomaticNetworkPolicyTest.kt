package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutomaticNetworkPolicyTest {
    @Test fun manualDefaultAndNetworkRestrictionsAreDistinct() {
        assertTrue(AutomaticNetworkPolicy.allows("any", false, false, false, false))
        assertTrue(AutomaticNetworkPolicy.allows("wifi", true, true, false, true))
        assertFalse(AutomaticNetworkPolicy.allows("wifi", true, false, true, false))
        assertTrue(AutomaticNetworkPolicy.allows("mobile", true, false, true, false))
        assertFalse(AutomaticNetworkPolicy.allows("mobile", false, false, true, false))
        assertTrue(AutomaticNetworkPolicy.allows("unmetered", true, true, false, true))
        assertFalse(AutomaticNetworkPolicy.allows("unmetered", true, true, false, false))
        assertFalse(AutomaticNetworkPolicy.allows("unknown", true, true, true, true))
    }
}
