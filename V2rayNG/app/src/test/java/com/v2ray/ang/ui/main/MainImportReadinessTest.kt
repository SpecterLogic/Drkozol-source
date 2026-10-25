package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.ClientPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

// Test-only main dispatcher replacement; revisit when coroutines-test stabilizes this API.
@OptIn(ExperimentalCoroutinesApi::class)
class MainImportReadinessTest {
    @Test fun firstSubscriptionKeepsLoadingUntilRowsAreReadyWithoutSelectingAServer() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val imported = AtomicBoolean(false)
        val enteredRead = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val store = ViewModelStore()
        try {
            val subscription = SubscriptionItem(remarks = "Fixture", url = "https://example.invalid/sub")
            val profile = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "fixture-sub",
                remarks = "Fixture server", description = "Fixture", server = "example.invalid", serverPort = "443")
            val source = mock<MainDataSource>()
            whenever(source.mainServiceEvent).thenReturn(emptyFlow())
            whenever(source.getSelectedSubscriptionId()).thenReturn("")
            whenever(source.clientPreferences()).thenReturn(ClientPreferences(onboardingCompleted = true))
            whenever(source.backgroundProbeRevision()).thenReturn("")
            whenever(source.getSubscriptions()).thenAnswer {
                if (imported.get()) listOf(SubscriptionCache("fixture-sub", subscription)) else emptyList()
            }
            whenever(source.getSubscriptionItem(any())).thenAnswer {
                if (it.getArgument<String>(0) == "fixture-sub") subscription else null
            }
            whenever(source.getServerGuidList(any())).thenAnswer {
                if (imported.get() && it.getArgument<String>(0) == "fixture-sub") {
                    enteredRead.countDown()
                    check(allowRead.await(5, TimeUnit.SECONDS))
                    listOf("fixture-server")
                } else emptyList()
            }
            whenever(source.decodeServerConfig("fixture-server")).thenReturn(profile)
            whenever(source.importBatchConfig(anyOrNull(), any(), eq(true))).thenAnswer {
                imported.set(true)
                0 to 1
            }
            whenever(source.updateConfigViaSub(any())).thenReturn(SubscriptionUpdateResult(configCount = 1, successCount = 1))
            val vm = MainViewModel(mock<Application>(), source)
            store.put("fixture", vm)
            assertTrue(withContext(Dispatchers.IO) { vm.awaitInitialPage() })
            assertTrue(vm.uiState.value.groups.isEmpty())
            vm.onAction(MainAction.ImportBatchConfig("https://example.invalid/sub"))
            assertTrue(withContext(Dispatchers.IO) { enteredRead.await(5, TimeUnit.SECONDS) })
            // Hold the real IO read: completion must not be published while the list is unavailable.
            val prematureCompletion = withContext(Dispatchers.IO) {
                withTimeoutOrNull(200) { vm.isLoading.first { !it } }
            }
            assertNull(prematureCompletion)
            allowRead.countDown()
            val rows = withContext(Dispatchers.IO) {
                withTimeout(5000) { vm.serverGroupState("fixture-sub").first { it.rows.isNotEmpty() } }
            }
            withContext(Dispatchers.IO) { withTimeout(5000) { vm.isLoading.first { !it } } }
            assertEquals(listOf("fixture-server"), rows.rows.map { it.guid })
            assertNull(vm.uiState.value.selectedGuid)
            verify(source, never()).setSelectServer("fixture-server")
        } finally {
            allowRead.countDown()
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
