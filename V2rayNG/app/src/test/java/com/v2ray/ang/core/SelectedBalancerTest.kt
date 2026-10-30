package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ProfileItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SelectedBalancerTest {
    @Test fun previousReleasedGroupsStillResolveTheirFullCandidateSet() {
        val old = Gson().fromJson("""{"configType":"POLICYGROUP","policyGroupSubscriptionId":"provider","policyGroupType":"roundRobin"}""", ProfileItem::class.java)
        assertNull(old.policyGroupServerIds)
        assertEquals(listOf("a", "b"), selectedBalancerIds(old.policyGroupServerIds, listOf("a", "b")))
    }
    @Test fun selectedMembersAreStableIdsAndNeverExpandToUnselectedPeers() {
        assertEquals(listOf("c", "a"), selectedBalancerIds(listOf("c", "a", "c", "removed"), listOf("a", "b", "c")))
        assertEquals(emptyList<String>(), selectedBalancerIds(emptyList(), listOf("a", "b")))
        assertEquals(emptyList<String>(), selectedBalancerIds(listOf("removed"), listOf("a", "b")))
    }
    @Test fun selectedIdsSurviveSaveAndRestore() {
        val profile = ProfileItem.create(com.v2ray.ang.enums.EConfigType.POLICYGROUP).apply { policyGroupServerIds = listOf("stable-guid-a", "stable-guid-b") }
        val restored = Gson().fromJson(Gson().toJson(profile), ProfileItem::class.java)
        assertEquals(profile.policyGroupServerIds, restored.policyGroupServerIds)
    }
}
