package com.v2ray.ang.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.insetSelection
import kotlinx.coroutines.delay

/** The ring is visual only; one separately inset semantic button owns activation. */
@Composable
internal fun ConnectionPower(state: MainUiState, diameter: Dp, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val minimal = state.account?.connectionStyle == "minimal"
    val ringWidth = if (minimal) 4.dp else 10.dp
    var pendingSweep by remember { mutableFloatStateOf(.04f) }
    var testRotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isTesting) {
        if (state.isTesting) {
            val rotation = androidx.compose.animation.core.Animatable(0f)
            while (true) {
                rotation.snapTo(0f)
                rotation.animateTo(360f, tween(1100, easing = LinearEasing)) { testRotation = value }
            }
        } else testRotation = 0f
    }
    LaunchedEffect(state.isConnecting) {
        pendingSweep = .04f
        while (state.isConnecting) {
            delay(120)
            // Progress reflects a pending acknowledgement, never a fabricated successful connection.
            pendingSweep += (.92f - pendingSweep) * .035f
        }
    }
    val progress by animateFloatAsState(if (state.isConnecting) pendingSweep else if (state.isRunning) 1f else 0f,
        tween(420), label = "connection ring")
    val inset by animateFloatAsState(if (state.isRunning && !state.isConnecting) .76f else if (pressed) .80f else .84f, tween(240), label = "power inset")
    val label = stringResource(if (state.isTesting) R.string.dr_cancel_test else if (state.isRunning) R.string.dr_disconnect else R.string.dr_connect)
    val description = stringResource(if (state.isTesting) R.string.connection_test_testing else if (state.isConnecting) R.string.dr_connecting else if (state.isRunning) R.string.dr_connected else R.string.connection_not_connected)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = ringWidth.toPx()
            drawCircle(colors.onSurfaceVariant.copy(alpha = .13f), radius = size.minDimension / 2 - width / 2, style = Stroke(width))
            if (state.isTesting || progress > 0f) drawArc(colors.primary, if (state.isTesting) testRotation - 90f else -90f, if (state.isTesting) 100f else 360f * progress, false,
                topLeft = Offset(width / 2, width / 2), size = Size(size.width - width, size.height - width),
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Surface(onClick = action, interactionSource = interaction, enabled = state.isTesting || (!state.isConnecting && (state.isRunning ||
            (!state.backgroundTesting && !state.accountLocked && !state.selectedGuid.isNullOrEmpty()))),
            shape = CircleShape, color = colors.surfaceContainer,
            shadowElevation = if (state.isRunning || pressed) 0.dp else 5.dp,
            modifier = Modifier.size(diameter * inset).semantics {
                contentDescription = label; stateDescription = description
            }) {
            Box(Modifier.fillMaxSize().insetSelection(state.isRunning || pressed, CircleShape, colors.surfaceContainer, colors.primary), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(65.dp)) {
                    val ink = if (state.isRunning || state.isConnecting) colors.primary else colors.onSurfaceVariant
                    if (state.isRunning && !state.isConnecting) {
                        val shield = Path().apply {
                            moveTo(size.width * .5f, size.height * .04f)
                            lineTo(size.width * .91f, size.height * .24f)
                            lineTo(size.width * .91f, size.height * .54f)
                            cubicTo(size.width * .91f, size.height * .77f, size.width * .71f, size.height * .92f, size.width * .5f, size.height * .98f)
                            cubicTo(size.width * .29f, size.height * .92f, size.width * .09f, size.height * .77f, size.width * .09f, size.height * .54f)
                            lineTo(size.width * .09f, size.height * .24f); close()
                        }
                        drawPath(shield, ink, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    } else {
                        drawArc(ink, -48f, 276f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                        drawLine(ink, Offset(center.x, 0f), Offset(center.x, size.height * .45f), 6.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}
