package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.DrKozolPalette

internal fun themeArtworkResource(palette: DrKozolPalette, dark: Boolean): Int? = when (palette) {
    DrKozolPalette.KYOTO -> if (dark) R.drawable.dr_theme_kyoto_dark else R.drawable.dr_theme_kyoto_light
    DrKozolPalette.BAIKAL -> if (dark) R.drawable.dr_theme_baikal_dark else R.drawable.dr_theme_baikal_light
    DrKozolPalette.JADE -> if (dark) R.drawable.dr_theme_jade_dark else R.drawable.dr_theme_jade_light
    DrKozolPalette.PORCELAIN -> if (dark) R.drawable.dr_theme_silk_dark else R.drawable.dr_theme_silk_light
    else -> null
}

/** Coil owns sampled decoding off the UI thread and caches only the displayed artwork. */
@Composable
internal fun ThemeArtwork(palette: DrKozolPalette, dark: Boolean, modifier: Modifier, preview: Boolean) {
    val resource = themeArtworkResource(palette, dark) ?: return
    Box(modifier) {
        AsyncImage(model = resource, contentDescription = null, contentScale = ContentScale.Crop, alignment = AbsoluteAlignment.CenterRight,
            modifier = Modifier.matchParentSize())
        if (!preview) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = .88f), MaterialTheme.colorScheme.background.copy(alpha = .58f), MaterialTheme.colorScheme.background.copy(alpha = .20f)))))
    }
}
