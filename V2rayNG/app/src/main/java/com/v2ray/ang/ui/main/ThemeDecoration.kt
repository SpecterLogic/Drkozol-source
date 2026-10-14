package com.v2ray.ang.ui.main

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.v2ray.ang.ui.compose.DrKozolPalette

/** Static illustrations stay on the backdrop, without bitmap decoding or animation loops. */
internal fun Modifier.themeDecoration(palette: DrKozolPalette, dark: Boolean, dynamic: Boolean): Modifier =
    drawBehind {
        if (dynamic || palette == DrKozolPalette.OLED) return@drawBehind
        withTransform({ scale(size.width / 400f, size.height / 850f, Offset.Zero) }) {
            if (palette == DrKozolPalette.JAPAN && dark) {
                val red = Color(0xFFCC263B)
                drawCircle(red.copy(alpha = .24f), 103f, Offset(312f, 182f))
                drawCircle(red.copy(alpha = .2f), 116f, Offset(312f, 182f), style = Stroke(1f))
            } else if (palette == DrKozolPalette.JAPAN) {
                val pink = Color(0xFFD86682)
                drawCircle(pink.copy(alpha = .17f), 85f, Offset(320f, 178f))
                // A few separated petals keep the spring theme quiet behind text and controls.
                listOf(Offset(356f, 92f), Offset(382f, 240f), Offset(28f, 420f)).forEach { center ->
                    repeat(5) { petal ->
                        val angle = petal * 1.2566
                        drawCircle(pink.copy(alpha = .13f), 4f, center + Offset(kotlin.math.cos(angle).toFloat() * 5f, kotlin.math.sin(angle).toFloat() * 5f))
                    }
                }
            } else if (palette == DrKozolPalette.ANIME && !dark) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF93CBFF).copy(alpha = .38f), Color.Transparent), Offset(310f, 185f), 200f), 200f, Offset(310f, 185f))
                // Split comet tails and a layered sky skyline.
                drawLine(Color(0xFF767EFF).copy(alpha = .4f), Offset(390f, 72f), Offset(214f, 264f), 7f, StrokeCap.Round)
                drawLine(Color(0xFFF188BD).copy(alpha = .5f), Offset(398f, 94f), Offset(222f, 270f), 3f, StrokeCap.Round)
                drawCircle(Color.White, 4f, Offset(214f, 264f))
                repeat(24) { i -> drawCircle(Color.White.copy(alpha = .8f), if (i % 5 == 0) 2f else 1f, Offset((i * 67 % 390).toFloat(), (86 + i * 37 % 258).toFloat())) }
                repeat(3) { layer ->
                    val ridge = Path().apply { moveTo(0f, 700f); lineTo(0f, 507f + layer * 45f); repeat(7) { i -> lineTo(i * 70f, 440f + layer * 52f + (i % 3) * 27f) }; lineTo(400f, 700f); close() }
                    drawPath(ridge, Color(0xFF7678B3).copy(alpha = .07f + layer * .015f))
                }
            }
        }
    }
