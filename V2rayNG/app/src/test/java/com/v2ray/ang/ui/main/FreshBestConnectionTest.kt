package com.v2ray.ang.ui.main

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.ClientPreferences
import com.v2ray.ang.ui.compose.AppSnackbarManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.concurrent.ConcurrentHashMap

class FreshBestConnectionTest {
    @Test fun cachedHealthyServerMustPassAFreshProbeBeforeConnection() = verifyFreshResult(40)
    @Test fun reachableTcpAndOldRealResultsCannotRescueFailedFreshConnections() = verifyFreshResult(-1)
    @Test fun cancelledFreshProbeDoesNotSelectACachedServer() = verifyFreshResult(40, cancelled = true)
    @Test fun emptyGroupDoesNotStartAProbeOrSelectAServer() = verifyFreshResult(40, empty = true)
    @Test fun missingDaemonReplyReleasesTestingWithoutSelectingStaleResults() = verifyFreshResult(40, stalled = true)
    @Test fun powerCancellationReleasesTestingAndIgnoresLateSuccess() = verifyFreshResult(40, powerCancelled = true)

    // Test-only dispatcher replacement; reevaluate when coroutines-test dispatchers become stable.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyFreshResult(newDelay: Long, cancelled: Boolean = false, empty: Boolean = false,
        stalled: Boolean = false, powerCancelled: Boolean = false) = runBlocking {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        val notices = launch(main) {
            AppSnackbarManager.messages.collect { }
        }
        val store = ViewModelStore()
        var modelJob: Job? = null
        try {
            val events = MutableSharedFlow<MainServiceEvent>(extraBufferCapacity = 16)
            val requested = CompletableDeferred<String>()
            val source = mock<MainDataSource>()
            val subscription = SubscriptionItem(remarks = "Fixture", url = "https://example.invalid/sub")
            val profiles = (if (empty) emptyList() else listOf("old", "fresh")).associateWith {
                ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group",
                    remarks = it, description = "Fixture", server = "example.invalid", serverPort = "443")
            }
            val measurements = ConcurrentHashMap<String, ServerAffiliationInfo>().apply {
                put("old", ServerAffiliationInfo(realDelayMillis = 1, tcpDelayMillis = 1))
                put("fresh", ServerAffiliationInfo(realDelayMillis = 500, tcpDelayMillis = 2))
            }
            whenever(source.mainServiceEvent).thenReturn(events)
            whenever(source.getSelectedSubscriptionId()).thenReturn("group")
            whenever(source.getSelectServer()).thenReturn(if (empty) null else "old")
            whenever(source.getSubscriptions()).thenReturn(listOf(SubscriptionCache("group", subscription)))
            whenever(source.getSubscriptionItem(any())).thenReturn(subscription)
            whenever(source.getServerGuidList(any())).thenReturn(profiles.keys.toList())
            whenever(source.decodeServerConfig(any())).thenAnswer { profiles[it.getArgument<String>(0)] }
            whenever(source.decodeAffiliationInfo(any())).thenAnswer { measurements[it.getArgument<String>(0)] }
            whenever(source.backgroundProbeRevision()).thenReturn("")
            whenever(source.clientPreferences()).thenReturn(ClientPreferences(onboardingCompleted = true))
            whenever(source.getString(any<Int>())).thenReturn("Fixture notice")
            doAnswer {
                val message = it.getArgument<TestServiceMessage>(0)
                assertFalse(message.onlyTcp)
                requested.complete(it.getArgument<String>(1))
                Unit
            }.whenever(source).sendMsg2TestService(any(), anyOrNull())
            val vm = MainViewModel(mock<AngApplication>(), source)
            modelJob = vm.viewModelScope.coroutineContext[Job]
            store.put("fixture", vm)
            assertTrue(withContext(Dispatchers.IO) { vm.awaitInitialPage() })
            if (empty) {
                assertFalse(withContext(Dispatchers.IO) { vm.chooseBestForConnection() })
                verify(source, never()).sendMsg2TestService(any(), anyOrNull())
                verify(source, never()).setSelectServer(any())
                return@runBlocking
            }
            assertEquals(1L, vm.uiState.value.realResults["old"])
            val choice = async(Dispatchers.IO) { vm.chooseBestForConnection() }
            val requestId = withContext(Dispatchers.IO) { withTimeout(5000) { requested.await() } }
            assertTrue(vm.uiState.value.isTesting)
            verify(source, never()).setSelectServer(any())
            if (stalled || powerCancelled) {
                if (powerCancelled) vm.onAction(MainAction.CancelTesting)
                else {
                    main.scheduler.advanceTimeBy(45_001)
                    main.scheduler.runCurrent()
                }
                assertFalse(vm.uiState.value.isTesting)
                assertFalse(withContext(Dispatchers.IO) { withTimeout(5000) { choice.await() } })
                events.emit(MainServiceEvent.MeasureConfigSuccess(RealPingResult("fresh", 1), requestId))
                events.emit(MainServiceEvent.MeasureConfigFinish(requestId))
                assertEquals("old", vm.uiState.value.selectedGuid)
                assertFalse(vm.uiState.value.isTesting)
                verify(source, never()).setSelectServer(any())
                vm.testAllRealPing()
                assertTrue(vm.uiState.value.isTesting)
                vm.cancelAllPing()
                return@runBlocking
            }
            if (cancelled) {
                events.emit(MainServiceEvent.MeasureConfigCancelled(requestId))
                assertFalse(withContext(Dispatchers.IO) { withTimeout(5000) { choice.await() } })
                assertEquals("old", vm.uiState.value.selectedGuid)
                verify(source, never()).setSelectServer(any())
                return@runBlocking
            }
            measurements["old"] = ServerAffiliationInfo(realDelayMillis = -1, tcpDelayMillis = 1)
            measurements["fresh"] = ServerAffiliationInfo(realDelayMillis = newDelay, tcpDelayMillis = 2)
            events.emit(MainServiceEvent.MeasureConfigSuccess(RealPingResult("old", -1), requestId))
            events.emit(MainServiceEvent.MeasureConfigSuccess(RealPingResult("fresh", newDelay), requestId))
            withContext(Dispatchers.IO) {
                withTimeout(5000) { vm.uiState.first { it.realResults["fresh"] == newDelay } }
            }
            events.emit(MainServiceEvent.MeasureConfigFinish(requestId))
            assertEquals(newDelay > 0, withContext(Dispatchers.IO) { withTimeout(5000) { choice.await() } })
            if (newDelay > 0) {
                assertEquals("fresh", vm.uiState.value.selectedGuid)
                verify(source).setSelectServer("fresh")
            } else {
                assertEquals("old", vm.uiState.value.selectedGuid)
                verify(source, never()).setSelectServer(any())
            }
        } finally {
            store.clear()
            modelJob?.join()
            notices.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
