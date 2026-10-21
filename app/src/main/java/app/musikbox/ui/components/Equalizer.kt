package app.musikbox.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Three bouncing bars marking the track that's playing. Frozen when paused. */
@Composable
fun Equalizer(color: Color, playing: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val t = rememberInfiniteTransition(label = "eq")
    val durations = intArrayOf(520, 680, 450)
    val bars = durations.mapIndexed { i, d ->
        t.animateFloat(
            initialValue = 0.25f + i * 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    val still = floatArrayOf(0.55f, 0.9f, 0.4f)
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.14f
        val w = (this.size.width - gap * 2) / 3f
        for (i in 0..2) {
            val frac = if (playing) bars[i].value else still[i]
            val h = this.size.height * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (w + gap), this.size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2.5f),
            )
        }
    }
}
