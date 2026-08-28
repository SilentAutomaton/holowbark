package net.holowbark.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.holowbark.ui.Ash
import net.holowbark.ui.Brick
import net.holowbark.ui.Lichen
import net.holowbark.ui.Resin
import net.holowbark.vpn.LayerState

/** The most segments worth drawing; past this they stop being countable anyway. */
private const val MAX_SEGMENTS = 24
private const val MIN_SEGMENTS = 8

/**
 * Two arcs around the connect button, drawn from the tunnel's real state.
 *
 * The outer arc is the Yggdrasil overlay, split into one segment per connected
 * peer, so bringing the tunnel up is something you watch assemble rather than a
 * spinner that tells you nothing. The inner arc is the tunnel running through it,
 * breathing while it waits for the server and solid once it answers.
 */
@Composable
fun MeshRing(
    yggState: LayerState,
    yggPeers: Int,
    awgState: LayerState,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp,
) {
    val animated = animationsEnabled()

    // Only the layer that is still working animates; a settled ring is completely
    // still, which is what makes the motion mean something when it appears.
    val transition = rememberInfiniteTransition(label = "mesh")
    val pulse by if (animated && awgState == LayerState.STARTING) {
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    } else {
        rememberStatic(1f)
    }

    val segments = (yggPeers.coerceAtLeast(0)).coerceAtMost(MAX_SEGMENTS)
    val slots = maxOf(segments, MIN_SEGMENTS)

    Canvas(modifier.size(diameter)) {
        val outerStroke = size.minDimension * 0.035f
        val innerStroke = size.minDimension * 0.022f
        val outerInset = outerStroke / 2f
        val innerInset = outerStroke * 2.4f + innerStroke / 2f

        val yggColor = when (yggState) {
            LayerState.UP -> Lichen
            LayerState.STARTING -> Lichen
            LayerState.ERROR -> Brick
            LayerState.IDLE -> Ash
        }

        // Outer: one lit segment per connected peer, the rest left as the unlit ring.
        val gap = 3.5f
        val sweep = 360f / slots - gap
        repeat(slots) { i ->
            val lit = i < segments
            drawArc(
                color = if (lit) yggColor else Ash.copy(alpha = 0.22f),
                startAngle = -90f + i * (360f / slots) + gap / 2f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(outerInset, outerInset),
                size = Size(size.width - outerInset * 2, size.height - outerInset * 2),
                style = Stroke(width = outerStroke, cap = StrokeCap.Round),
            )
        }

        // Inner: the tunnel itself, a single unbroken arc — it is one connection.
        val awgColor = when (awgState) {
            LayerState.UP -> Resin
            LayerState.STARTING -> Resin.copy(alpha = pulse)
            LayerState.ERROR -> Brick
            LayerState.IDLE -> Color.Transparent
        }
        if (awgColor != Color.Transparent) {
            drawArc(
                color = awgColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(innerInset, innerInset),
                size = Size(size.width - innerInset * 2, size.height - innerInset * 2),
                style = Stroke(width = innerStroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun rememberStatic(value: Float) = remember { mutableFloatStateOf(value) }

/** False when the user has turned animations off system-wide. */
@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
