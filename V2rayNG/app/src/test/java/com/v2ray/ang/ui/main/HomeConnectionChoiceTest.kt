package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeConnectionChoiceTest {
    @Test
    fun restoredBalancerIsRecognizedFromTheSelectedProfile() {
        val state = MainUiState(selectedGuid = "selected")
        assertEquals(HomeConnectionChoice.MANUAL, state.homeConnectionChoice)
        val balancer = ProfileItem(configType = EConfigType.POLICYGROUP)
        assertEquals(HomeConnectionChoice.BALANCER,
            state.withSelectedServerSnapshot("selected", balancer).homeConnectionChoice)
        assertEquals(state, state.withSelectedServerSnapshot("stale", balancer))
    }

    @Test
    fun selectedMembershipRestoresItsOwnHighlightEvenWhenMembersHaveDisappeared() {
        val state = MainUiState(selectedGuid = "selected")
        val profile = ProfileItem(configType = EConfigType.POLICYGROUP).apply { policyGroupServerIds = listOf("a", "b") }
        assertEquals(HomeConnectionChoice.SELECTED_BALANCER, state.withSelectedServerSnapshot("selected", profile).homeConnectionChoice)
        profile.policyGroupServerIds = emptyList()
        assertEquals(HomeConnectionChoice.SELECTED_BALANCER, state.withSelectedServerSnapshot("selected", profile).homeConnectionChoice)
        assertEquals(HomeConnectionChoice.MANUAL, state.withSelectedServerSnapshot("selected", null).homeConnectionChoice)
    }

    @Test
    fun missingAndOrdinaryProfilesClearABalancerHighlightWhileBestIsRetained() {
        val state = MainUiState(selectedGuid = "selected", homeConnectionChoice = HomeConnectionChoice.BALANCER)
        val ordinary = ProfileItem(configType = EConfigType.VLESS)
        assertEquals(HomeConnectionChoice.MANUAL, state.withSelectedServerSnapshot("selected", ordinary).homeConnectionChoice)
        assertEquals(HomeConnectionChoice.MANUAL, state.withSelectedServerSnapshot("selected", null).homeConnectionChoice)
        assertEquals(HomeConnectionChoice.BEST,
            state.copy(homeConnectionChoice = HomeConnectionChoice.BEST).withSelectedServerSnapshot("selected", ordinary).homeConnectionChoice)
    }
}
