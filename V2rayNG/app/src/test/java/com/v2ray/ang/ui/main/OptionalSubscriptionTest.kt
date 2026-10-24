package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.ClientPreferences
import com.v2ray.ang.handler.effectiveIpPreference
import com.v2ray.ang.handler.workspaceAvailable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OptionalSubscriptionTest {
    @Test fun cleanInstallOffersOnboardingAndAnExplicitSkipPersists() {
        val initial = MainUiState()
        assertFalse(workspaceAvailable(false, false))
        assertFalse(initial.subscriptionReady)
        assertFalse(initial.preferences.onboardingCompleted)
        val chosen = initial.preferences.copy(onboardingCompleted = true)
        val saved = initial.withClientPreferencesSave(chosen, true)
        assertTrue(saved.subscriptionReady)
        assertTrue(saved.preferences.onboardingCompleted)
        assertTrue(workspaceAvailable(saved.preferences.onboardingCompleted, false))
        assertTrue(workspaceAvailable(false, true))
        assertTrue(saved.withClientPreferencesSave(chosen, false).preferencesError)
    }

    @Test fun aFailedWriteNeverBypassesOnboardingOrOverwritesPreferences() {
        val initial = MainUiState()
        val failed = initial.withClientPreferencesSave(initial.preferences.copy(onboardingCompleted = true), false)
        assertFalse(failed.subscriptionReady)
        assertFalse(failed.preferences.onboardingCompleted)
        assertTrue(failed.preferencesError)
        assertFalse(failed.settingsSave.successful)
        val recovered = failed.withClientPreferencesSave(failed.preferences.copy(onboardingCompleted = true), true)
        assertTrue(recovered.subscriptionReady)
        assertFalse(recovered.preferencesError)
    }

    @Test fun editingAnotherPanelPreservesTheSkipChoiceLanguageAndIpPreference() {
        val value = ClientPreferences(onboardingCompleted = true, ipPreference = "ipv6", languageCode = "fa")
        val edited = value.copy(pingUrl = "https://example.com/204")
        assertTrue(edited.onboardingCompleted)
        assertEquals("fa", edited.languageCode)
        assertEquals("ipv6", edited.ipPreference)
    }

    @Test fun anInvalidIpPreferenceCannotBeSaved() {
        assertFalse(com.v2ray.ang.handler.SettingsManager.saveClientPreferences(ClientPreferences(ipPreference = "invalid")))
    }

    @Test fun ipFamilyMigrationHonorsLegacyIpv6AndRejectsUnknownValues() {
        assertEquals("auto", effectiveIpPreference(null, false))
        assertEquals("ipv6", effectiveIpPreference(null, true))
        assertEquals("ipv4", effectiveIpPreference("ipv4", true))
        assertEquals("auto", effectiveIpPreference("auto", true))
        assertEquals("ipv6", effectiveIpPreference("invalid", true))
        assertEquals("auto", effectiveIpPreference("", false))
    }
}
