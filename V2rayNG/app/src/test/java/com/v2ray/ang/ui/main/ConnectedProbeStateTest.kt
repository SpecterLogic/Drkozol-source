package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConnectedProbeStateTest {
    @Test
    fun selectedSummaryComesFromTheSameProfileAndClearsForMissingProfiles() {
        val state = MainUiState(selectedGuid = "current")
        assertNull(state.selectedServerSummary)
        val name = "Sweden \uD83C\uDDF8\uD83C\uDDEA"
        val profile = ProfileItem(configType = EConfigType.VLESS, remarks = name, network = "xhttp", security = "tls")
        val updated = state.withSelectedServerSnapshot("current", profile)
        assertEquals(SelectedServerSummary("current", name, "VLESS / xhttp / tls", "\uD83C\uDDF8\uD83C\uDDEA"), updated.selectedServerSummary)
        assertEquals(updated, updated.withSelectedServerSnapshot("stale", profile))
        assertNull(updated.withSelectedServerSnapshot("current", null).selectedServerSummary)
        assertNull(MainUiState().withSelectedServerSnapshot(null, profile).selectedServerSummary)
    }

    @Test
    fun initialStateHasNoFabricatedConnectedDelay() {
        val state = MainUiState()
        assertNull(state.connectedTestGuid)
        assertNull(state.connectedTestResult)
    }

    @Test
    fun currentConnectedSuccessAndFailureArePresentedByGuid() {
        val state = MainUiState(isRunning = true, selectedGuid = "current")
        val success = ConnectionTestResult(74)
        val updated = state.withConnectedTestResult("current", success)
        assertEquals("current", updated.connectedTestGuid)
        assertEquals(success, updated.connectedTestResult)
        val failure = ConnectionTestResult(-1, "unavailable")
        assertEquals(failure, updated.withConnectedTestResult("current", failure).connectedTestResult)
    }

    @Test
    fun emptyInvalidStoppedAndReplacedProfilesRejectResults() {
        val result = ConnectionTestResult(74)
        val running = MainUiState(isRunning = true, selectedGuid = "current")
        listOf(null, "", "replaced").forEach { assertEquals(running, running.withConnectedTestResult(it, result)) }
        val stopped = running.copy(isRunning = false)
        assertEquals(stopped, stopped.withConnectedTestResult("current", result))
        val replaced = running.copy(selectedGuid = "new")
        assertEquals(replaced, replaced.withConnectedTestResult("current", result))
    }
}
