package com.v2ray.ang.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

class DrKozolPaletteTest {
    @Test
    fun missingPaletteUsesOceanWhileExplicitPrecedingChoicesRemain() {
        assertEquals(DrKozolPalette.OCEAN, DrKozolPalette.fromSetting(null))
        assertEquals(DrKozolPalette.FOREST, DrKozolPalette.fromSetting("forest"))
        assertEquals(DrKozolPalette.ANIME, DrKozolPalette.fromSetting("anime"))
    }

    @Test
    fun samuraiNightRetainsBlackBackdropAndRedAccent() {
        val night = DrKozolPalette.JAPAN.colors(true)
        assertEquals(0xFF000000, night.backgroundEnd)
        assertEquals(0xFFFF7C86, night.accent)
        assertEquals(0xFF000000, DrKozolPalette.OLED.colors(true).background)
    }

    @Test
    fun precedingReleaseAndInvalidValuesKeepForest() {
        listOf("", "unknown", "MAROON", "1").forEach {
            assertEquals(DrKozolPalette.FOREST, DrKozolPalette.fromSetting(it))
        }
        assertFalse(DrKozolPalette.isValidSetting(""))
        assertFalse(DrKozolPalette.isValidSetting("unknown"))
    }

    @Test
    fun everyPaletteRetainsItsStableSettingId() {
        assertEquals(setOf("forest", "maroon", "purple", "ocean", "sunset", "oled", "japan", "anime", "aurora", "sandstone", "graphite"), DrKozolPalette.entries.map { it.settingId }.toSet())
        DrKozolPalette.entries.forEach {
            assertTrue(DrKozolPalette.isValidSetting(it.settingId))
            assertEquals(it, DrKozolPalette.fromSetting(it.settingId))
        }
        assertEquals(0xFF0A1F17, DrKozolPalette.FOREST.colors(true).background)
        assertEquals(0xFFC9A45A, DrKozolPalette.FOREST.colors(true).accent)
    }

    @Test
    fun allFixedModesHaveReadableTextAndControls() {
        fun luminance(argb: Long): Double = listOf(16, 8, 0).map { shift ->
            val component = ((argb shr shift) and 255).toDouble() / 255
            if (component <= .04045) component / 12.92 else ((component + .055) / 1.055).pow(2.4)
        }.let { it[0] * .2126 + it[1] * .7152 + it[2] * .0722 }
        fun contrast(first: Long, second: Long): Double {
            val a = luminance(first); val b = luminance(second)
            return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        }
        DrKozolPalette.entries.forEach { palette -> listOf(false, true).forEach { dark ->
            val colors = palette.colors(dark)
            assertTrue(contrast(colors.ink, colors.surface) >= 4.5, "$palette/$dark body text")
            assertTrue(contrast(colors.muted, colors.surface) >= 4.5, "$palette/$dark secondary text")
            assertTrue(contrast(colors.accent, colors.surface) >= 4.5, "$palette/$dark action text")
            assertTrue(contrast(colors.onAccent, colors.accent) >= 4.5, "$palette/$dark filled action")
            val selectedSurface = insetSelectionColor(Color(colors.surface), Color(colors.accent)).toArgb().toLong()
            assertTrue(contrast(colors.ink, selectedSurface) >= 4.5, "$palette/$dark inset body")
            assertTrue(contrast(colors.muted, selectedSurface) >= 4.5, "$palette/$dark inset secondary text")
            assertTrue(contrast(colors.accent, selectedSurface) >= 4.5, "$palette/$dark inset action")
        } }
    }
}
