package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionPresentationTest {
    @Test fun completedSaveBelongsOnlyToTheSubmittingFormAndNeverReplays() {
        val initial = MainUiState()
        assertEquals(0L, initial.settingsSave.revision)
        assertFalse(initial.settingsSave.completes("preferences", null))
        val saved = initial.withSettingsSave("preferences", true)
        assertTrue(saved.settingsSave.completes("preferences", 0))
        assertTrue(saved.settingsSave.successful)
        assertFalse(saved.preferencesError)
        assertNull(initial.settingsSaveInFlight)
        assertNull(initial.copy(settingsSaveInFlight = "preferences").withSettingsSave("preferences", true).settingsSaveInFlight)
        assertNull(initial.copy(settingsSaveInFlight = "preferences").withSettingsSave("preferences", false).settingsSaveInFlight)
        assertFalse(saved.settingsSave.completes("provider-a", 0))
        assertFalse(saved.settingsSave.completes("preferences", saved.settingsSave.revision))
        assertFalse(saved.settingsSave.completes("preferences", null))
        val failed = saved.withSettingsSave("provider-a", false)
        assertTrue(failed.settingsSave.completes("provider-a", 1))
        assertFalse(failed.settingsSave.successful)
        assertTrue(failed.preferencesError)
        val retry = failed.withSettingsSave("provider-a", true)
        assertTrue(retry.settingsSave.completes("provider-a", 2))
        assertFalse(retry.preferencesError)
    }

    @Test fun bannerDeadlineCannotDismissANewerEvenIdenticalUpdateResult() {
        val previous = com.v2ray.ang.dto.SubscriptionUpdateResult(configCount = 24, successCount = 1)
        val next = previous.copy()
        assertNull(MainUiState().expireUpdateSummary(previous).updateSummary)
        assertNull(MainUiState(updateSummary = previous).expireUpdateSummary(previous).updateSummary)
        val newer = MainUiState(updateSummary = next)
        assertSame(newer, newer.expireUpdateSummary(previous))
        assertSame(next, newer.expireUpdateSummary(previous).updateSummary)
        assertNull(newer.expireUpdateSummary(next).updateSummary)
        val failed = com.v2ray.ang.dto.SubscriptionUpdateResult(failureCount = 1)
        assertNull(MainUiState(updateSummary = failed).expireUpdateSummary(failed).updateSummary)
    }

    private fun row(id: String) = ServerRowUiModel(id, ProfileItem(configType = EConfigType.VLESS), id, "", "VLESS", 0, "")

    @Test fun incrementalResultsSortHealthyThenPendingThenFailedWithStableTies() {
        val rows = listOf("slow", "pending", "failed", "tie-a", "tie-b").map(::row)
        val results = mapOf("slow" to 300L, "failed" to -1L, "tie-a" to 25L, "tie-b" to 25L)
        assertEquals(listOf("tie-a", "tie-b", "slow", "pending", "failed"), sortedServerRows(rows, results).map { it.guid })
        assertEquals(rows, sortedServerRows(rows, emptyMap()))
        assertEquals(emptyList<ServerRowUiModel>(), sortedServerRows(emptyList(), results))
    }

    @Test fun sortingDoesNotChooseOrModifyProfiles() {
        val source = listOf(row("a"), row("b"))
        val sorted = sortedServerRows(source, mapOf("b" to 10L, "a" to 40L))
        assertSame(source[1].profile, sorted[0].profile)
        assertEquals(listOf("a", "b"), source.map { it.guid })
        assertNull(MainUiState().selectedGuid)
    }

    @Test fun onlyTransportChangesNeedAnotherVpnService() {
        val xray = ProfileItem(configType = EConfigType.VLESS)
        val awg = ProfileItem(configType = EConfigType.CUSTOM, amneziaConfig = "fixture")
        assertTrue(requiresTransportHandover(xray, awg))
        assertTrue(requiresTransportHandover(awg, xray))
        assertFalse(requiresTransportHandover(xray, xray.copy()))
        assertFalse(requiresTransportHandover(awg, awg.copy()))
        assertFalse(requiresTransportHandover(null, awg))
    }

    @Test fun currentResultUpdatesRealOrderingOnlyForTheActiveGuid() {
        val state = MainUiState(isRunning = true, selectedGuid = "active", tcpResults = mapOf("other" to 4L))
        val updated = state.withConnectedTestResult("active", ConnectionTestResult(40))
        assertEquals(mapOf("active" to 40L), updated.realResults)
        assertEquals(state.tcpResults, updated.tcpResults)
        assertEquals(state, state.withConnectedTestResult("stale", ConnectionTestResult(1)))
        assertEquals(state.copy(isRunning = false), state.copy(isRunning = false).withConnectedTestResult("active", ConnectionTestResult(1)))
    }

    @Test fun pendingConnectionAndBackgroundWorkBlockStandalonePeers() {
        assertTrue(MainUiState().canStartBulkProbe)
        assertFalse(MainUiState(isRunning = true).canStartBulkProbe)
        assertFalse(MainUiState(isConnecting = true).canStartBulkProbe)
        assertFalse(MainUiState(backgroundTesting = true).canStartBulkProbe)
    }
}
