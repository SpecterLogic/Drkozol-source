package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscriptionIndexTest {
    private val mainValues = mutableMapOf<String, String>()
    private val subValues = mutableMapOf<String, String>()
    private val settingValues = mutableMapOf<String, String>()
    private val settingFlags = mutableMapOf<String, Boolean>()

    @Test
    fun failedPreferenceWriteRestoresTheWholePrecedingSnapshot() {
        settingValues["dr_update_minutes"] = "120"
        val original = SettingsManager.clientPreferences()
        whenever(settings.encode(org.mockito.kotlin.eq("dr_update_minutes"), any<String>())).thenReturn(false, true)
        val proposed = original.copy(updateMinutes = 360, options = original.options + (ClientOption.VIBRATION to false))
        assertEquals(false, SettingsManager.saveClientPreferences(proposed))
        assertEquals(original, SettingsManager.clientPreferences())
    }

    @Test
    fun invalidSettingsDoNotPartiallyWriteStorage() {
        val original = SettingsManager.clientPreferences()
        assertEquals(false, SettingsManager.saveClientPreferences(original.copy(pingUrl = "http://example.test")))
        assertEquals(false, SettingsManager.saveClientPreferences(original.copy(options = original.options + (ClientOption.HOTSPOT to true))))
        assertEquals(original, SettingsManager.clientPreferences())
        verify(settings, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun refreshUpgradeEnablesInactiveLegacyUrlGroupsWithoutChangingSelectionOrEnabledCustomSchedule() {
        mainValues["SUB_IDS"] = JsonUtil.toJson(listOf("inactive", "custom"))
        mainValues["SELECTED_SERVER"] = "keep-selected"
        subValues["inactive"] = JsonUtil.toJson(SubscriptionItem(remarks = "Legacy", url = "https://legacy.example/sub", autoUpdate = false, updateInterval = 720))
        val custom = SubscriptionItem(remarks = "Custom", url = "https://custom.example/sub", autoUpdate = true, updateInterval = 30)
        subValues["custom"] = JsonUtil.toJson(custom)
        SettingsManager.migrateAutomaticRefresh()
        assertEquals(true, MmkvManager.decodeSubscription("inactive")?.autoUpdate)
        assertEquals(false, MmkvManager.decodeSubscription("inactive")?.customUpdateInterval)
        assertEquals(720L, MmkvManager.decodeSubscription("inactive")?.updateInterval)
        assertEquals(custom, MmkvManager.decodeSubscription("custom"))
        assertEquals("keep-selected", MmkvManager.getSelectServer())
        assertEquals(true, MmkvManager.decodeSettingsBool("dr_schedule_150_migrated"))
    }

    @Test
    fun emptyInternalDefaultIsHiddenAndUserCreatedDefaultRemainsVisible() {
        val builtIn = AppConfig.DEFAULT_SUBSCRIPTION_ID
        mainValues["SUB_IDS"] = JsonUtil.toJson(listOf(builtIn, "user-group"))
        subValues[builtIn] = JsonUtil.toJson(SubscriptionItem(remarks = "Default"))
        subValues["user-group"] = JsonUtil.toJson(SubscriptionItem(remarks = "Default"))
        assertEquals(listOf("user-group"), MmkvManager.decodeSubscriptions().map { it.guid })
        assertEquals(listOf(builtIn, "user-group"), MmkvManager.decodeSubsList())
    }

    @Test
    fun ungroupedManualProfilesStayReachableWithoutAnAutomaticGroupRecord() {
        val builtIn = AppConfig.DEFAULT_SUBSCRIPTION_ID
        mainValues["SUB_SERVERS_" + builtIn] = JsonUtil.toJson(listOf("manual-profile"))
        assertEquals(listOf(builtIn), MmkvManager.decodeSubscriptions().map { it.guid })
        assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        assertEquals(null, MmkvManager.decodeSubscription(builtIn))
    }

    @BeforeEach
    fun prepareStorage() {
        reset(settings)
        whenever(settings.decodeString(any())).thenAnswer { settingValues[it.getArgument<String>(0)] }
        whenever(settings.decodeString(any(), any<String>())).thenAnswer { settingValues[it.getArgument<String>(0)] ?: it.getArgument<String>(1) }
        whenever(settings.decodeBool(any(), any<Boolean>())).thenAnswer { settingFlags[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
        whenever(settings.encode(any<String>(), any<String>())).thenAnswer { settingValues[it.getArgument(0)] = it.getArgument(1); true }
        whenever(settings.encode(any<String>(), any<Boolean>())).thenAnswer { settingFlags[it.getArgument(0)] = it.getArgument(1); true }
        for ((storage, values) in listOf(main to mainValues, subs to subValues)) {
            reset(storage)
            whenever(storage.decodeString(any())).thenAnswer { values[it.getArgument<String>(0)] }
            whenever(storage.encode(any<String>(), any<String>())).thenAnswer {
                values[it.getArgument(0)] = it.getArgument(1)
                true
            }
            whenever(storage.allKeys()).thenAnswer { values.keys.toTypedArray() }
        }
    }

    @Test
    fun duplicateIdsKeepTheirFirstPositionWithoutWritingStorage() {
        val stored = """["second","first","second","third","first"]"""
        mainValues["SUB_IDS"] = stored

        assertEquals(listOf("second", "first", "third"), MmkvManager.decodeSubsList())
        assertEquals(stored, mainValues["SUB_IDS"])
        verify(main, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun duplicateIndexEntriesProduceOnlyOneSubscriptionRow() {
        mainValues["SUB_IDS"] = """["b","a","b","a"]"""
        subValues["a"] = JsonUtil.toJson(SubscriptionItem(remarks = "Alpha"))
        subValues["b"] = JsonUtil.toJson(SubscriptionItem(remarks = "Beta"))

        val rows = MmkvManager.decodeSubscriptions()

        assertEquals(listOf("b", "a"), rows.map { it.guid })
        assertEquals(listOf("Beta", "Alpha"), rows.map { it.subscription.remarks })
    }

    @Test
    fun repeatedAndBlankNamesDoNotMergeDifferentSubscriptions() {
        mainValues["SUB_IDS"] = """["a","b","c","d"]"""
        listOf("a" to "Same", "b" to "Same", "c" to "", "d" to " ").forEach { (id, name) ->
            subValues[id] = JsonUtil.toJson(SubscriptionItem(remarks = name))
        }

        assertEquals(listOf("a", "b", "c", "d"), MmkvManager.decodeSubscriptions().map { it.guid })
    }

    @Test
    fun decodedIndexRemainsMutableAndCanBeSavedInANewOrder() {
        mainValues["SUB_IDS"] = """["a","b","a"]"""
        val ids = MmkvManager.decodeSubsList()
        ids.remove("a")
        ids.add(0, "c")
        MmkvManager.encodeSubsList(ids)

        assertEquals("""["c","b"]""", mainValues["SUB_IDS"])
        assertEquals(listOf("c", "b"), MmkvManager.decodeSubsList())
    }

    @Test
    fun missingBlankAndEmptyIndexesRemainEmpty() {
        assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        listOf("", " ", "[]", "null").forEach { stored ->
            mainValues["SUB_IDS"] = stored
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun malformedIndexKeepsTheExistingEmptyFallback() {
        mockStatic(Log::class.java).use {
            mainValues["SUB_IDS"] = "{"
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    companion object {
        private val main: MMKV = mock()
        private val subs: MMKV = mock()
        private val settings: MMKV = mock()

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
                it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                MmkvManager.decodeSubscriptions()
                MmkvManager.decodeSettingsString("test-initialize")
            }
        }
    }
}
