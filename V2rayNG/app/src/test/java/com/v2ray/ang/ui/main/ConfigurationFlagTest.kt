package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConfigurationFlagTest {
    @Test
    fun findsTheActualFlagAtAnyPositionWithoutSplittingSurrogates() {
        val germany = "\uD83C\uDDE9\uD83C\uDDEA"
        val sweden = "\uD83C\uDDF8\uD83C\uDDEA"
        assertEquals(germany, configurationFlag(germany + " CDN"))
        assertEquals(sweden, configurationFlag("Sweden " + sweden))
        assertEquals(germany, configurationFlag("\uD83D\uDE80 CDN " + germany + sweden))
    }

    @Test
    fun unspecifiedCountriesAndIncompleteFlagsStayUnknown() {
        listOf("", "CDN", "panel.example.org", "Sweden", "\uD83C\uDDE9", "\uD83C", "\uD83D\uDE80").forEach {
            assertNull(configurationFlag(it))
        }
    }

    @Test
    fun titleRetainsMixedScriptAndNeverDisappearsWhenOnlyAFlagWasSupplied() {
        assertEquals("سرور Germany 01 / XHTTP", configurationName("🇩🇪 سرور Germany 01 / XHTTP"))
        assertEquals("🇩🇪", configurationName("🇩🇪"))
        assertEquals("CDN 02", configurationName("CDN 02"))
        assertEquals("", configurationName(""))
        assertEquals("CDN", configurationName("🇩🇪 CDN 🇩🇪"))
    }
}
