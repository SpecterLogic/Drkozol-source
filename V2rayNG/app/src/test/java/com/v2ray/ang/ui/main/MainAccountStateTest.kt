package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionUsage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainAccountStateTest {
    @Test fun `provider cards start empty and ignore stale removed identities`() {
        val state = MainUiState(groups = listOf(com.v2ray.ang.dto.GroupMapItem("current", "Provider")))
        assertTrue(state.accounts.isEmpty())
        assertTrue(state.providerDays.isEmpty())
        assertTrue(state.providerLocks.isEmpty())
        val populated = state.withProviderSnapshots(mapOf("current" to SubscriptionItem(), "removed" to SubscriptionItem(enabled = false)), 1)
        assertEquals(setOf("current"), populated.accounts.keys)
        assertNull(populated.providerDays["current"])
        assertFalse(populated.providerLocks["current"]!!)
        assertTrue(populated.withProviderSnapshots(emptyMap(), 2).accounts.isEmpty())
    }
    @Test fun `unselected provider expiry and renewed quota update on the owner clock`() {
        val state = MainUiState(groups = listOf(com.v2ray.ang.dto.GroupMapItem("other", "Other")), selectedGroupId = "selected")
        val account = SubscriptionItem(usage = SubscriptionUsage(upload = 10, download = 90, total = 1000, expire = 100))
        val before = state.withProviderSnapshots(mapOf("other" to account), 99)
        assertFalse(before.providerLocks["other"]!!)
        assertEquals(1L, before.providerDays["other"])
        val expired = before.withProviderSnapshots(mapOf("other" to account), 100)
        assertTrue(expired.providerLocks["other"]!!)
        assertEquals(0L, expired.providerDays["other"])
        val renewed = expired.withProviderSnapshots(mapOf("other" to account.copy(usage = account.usage!!.copy(expire = 86500))), 100)
        assertFalse(renewed.providerLocks["other"]!!)
        assertEquals(1L, renewed.providerDays["other"])
    }
    @Test fun `provider cards retain exhaustion disabled and unlimited distinctions`() {
        val state = MainUiState(groups = listOf(com.v2ray.ang.dto.GroupMapItem("a", "A")))
        assertTrue(state.withProviderSnapshots(mapOf("a" to SubscriptionItem(usage = SubscriptionUsage(upload = 10, download = 90, total = 100))), 1).providerLocks["a"]!!)
        assertTrue(state.withProviderSnapshots(mapOf("a" to SubscriptionItem(enabled = false)), 1).providerLocks["a"]!!)
        val unlimited = state.withProviderSnapshots(mapOf("a" to SubscriptionItem(usage = SubscriptionUsage(total = 0, expire = 0))), 1)
        assertFalse(unlimited.providerLocks["a"]!!)
        assertNull(unlimited.providerDays["a"])
    }
    private val initial = MainUiState(selectedGuid = "server-a", selectedGroupId = "group-a")
    @Test fun `initial state has no invented limits`() {
        assertNull(initial.account)
        assertNull(initial.accountDays)
        assertFalse(initial.accountLocked)
        assertNull(initial.withAccountSnapshot("server-a", "group-a", null, 100).accountDays)
    }
    @Test fun `account follows stable selection and discards stale poll results`() {
        val account = SubscriptionItem(usage = SubscriptionUsage(expire = 86401))
        val populated = initial.withAccountSnapshot("server-a", "group-a", account, 1)
        assertEquals(1L, populated.accountDays)
        assertFalse(populated.accountLocked)
        assertSame(initial, initial.withAccountSnapshot("server-b", "group-a", account, 1))
        assertSame(initial, initial.withAccountSnapshot("server-a", "group-b", account, 1))
    }
    @Test fun `expiry volume and disabled subscriptions lock the dashboard`() {
        val expired = SubscriptionItem(usage = SubscriptionUsage(expire = 100))
        assertTrue(initial.withAccountSnapshot("server-a", "group-a", expired, 100).accountLocked)
        val exhausted = SubscriptionItem(usage = SubscriptionUsage(upload = 10, download = 90, total = 100))
        assertTrue(initial.withAccountSnapshot("server-a", "group-a", exhausted, 1).accountLocked)
        assertTrue(initial.withAccountSnapshot("server-a", "group-a", SubscriptionItem(enabled = false), 1).accountLocked)
    }
    @Test fun `refreshing renewed account removes the lock`() {
        val old = initial.withAccountSnapshot("server-a", "group-a", SubscriptionItem(usage = SubscriptionUsage(expire = 100)), 100)
        val renewed = old.withAccountSnapshot("server-a", "group-a", SubscriptionItem(usage = SubscriptionUsage(expire = 864100)), 100)
        assertFalse(renewed.accountLocked)
        assertEquals(10L, renewed.accountDays)
    }
}
