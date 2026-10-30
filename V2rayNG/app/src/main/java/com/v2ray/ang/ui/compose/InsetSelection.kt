package com.v2ray.ang.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Inner edge shading conveys a held selection without extra layers or blur allocation. */
internal fun insetSelectionColor(surface: Color, accent: Color): Color {
    val dark = (surface.red + surface.green + surface.blue) < 1.5f
    val fill = lerp(surface, Color.Black, if (dark) .23f else .015f)
    return lerp(fill, accent, if (dark) .045f else .015f)
}

internal fun Modifier.insetSelection(active: Boolean, shape: Shape, surface: Color, accent: Color): Modifier =
    if (!active) this else clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val dark = (surface.red + surface.green + surface.blue) < 1.5f
        val fill = insetSelectionColor(surface, accent)
        val shadow = Brush.linearGradient(listOf(Color.Black.copy(alpha = if (dark) .65f else .22f), Color.Transparent), Offset.Zero, Offset(size.width, size.height))
        val shine = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (dark) .13f else .7f)), Offset.Zero, Offset(size.width, size.height))
        onDrawWithContent {
            drawOutline(outline, fill)
            drawContent()
            for (width in 6 downTo 1) {
                drawOutline(outline, shadow, alpha = .055f, style = Stroke(width.dp.toPx()))
            }
            drawOutline(outline, shine, alpha = .16f, style = Stroke(.75.dp.toPx()))
        }
    }
