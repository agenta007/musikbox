package app.musikbox.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import app.musikbox.ui.LocalAppContainer
import app.musikbox.visualizer.SpectrumAnalyzer
import app.musikbox.visualizer.VisualizerStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Feishin/audioMotion-style spectrum, drawn every frame from the playback PCM tap.
 * Colours run from the artwork tint at the base to near-white at the tips, with peak caps.
 */
@Composable
fun SpectrumVisualizer(style: VisualizerStyle, tint: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val tap = LocalAppContainer.current.visualizerTap
    val analyzer = remember(style) { SpectrumAnalyzer(bands = if (style == VisualizerStyle.Radial) 64 else 48) }
    val buffer = remember(analyzer) { FloatArray(analyzer.windowSize) }
    val isPlaying by rememberUpdatedState(playing)
    var frame by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        tap.active = true
        onDispose { tap.active = false }
    }
    LaunchedEffect(analyzer) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(1f, 100f)
                last = now
                val fresh = isPlaying && tap.read(buffer)
                analyzer.update(if (fresh) buffer else null, tap.sampleRate, dt)
                frame++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw every frame
        when (style) {
            VisualizerStyle.Bars -> drawBars(analyzer, tint, mirror = false)
            VisualizerStyle.Mirror -> drawBars(analyzer, tint, mirror = true)
            VisualizerStyle.Radial -> drawRadial(analyzer, tint)
            VisualizerStyle.Off -> Unit
        }
    }
}

private fun DrawScope.drawBars(a: SpectrumAnalyzer, tint: Color, mirror: Boolean) {
    val n = a.bands
    val gap = size.width / n * 0.22f
    val w = size.width / n - gap
    val base = if (mirror) size.height / 2 else size.height
    val maxH = if (mirror) size.height / 2 else size.height
    val brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.95f), lerp(tint, Color.White, 0.35f), tint),
        startY = base - maxH, endY = base,
    )
    for (i in 0 until n) {
        val x = i * (w + gap) + gap / 2
        val h = a.levels[i] * maxH
        if (h > 0.5f) {
            drawRoundRect(brush, Offset(x, base - h), Size(w, h), CornerRadius(w / 3))
            if (mirror) {
                // Reflection below the centre line.
                drawRoundRect(tint.copy(alpha = 0.35f), Offset(x, base), Size(w, h * 0.85f), CornerRadius(w / 3))
            }
        }
        val py = base - a.peaks[i] * maxH
        if (a.peaks[i] > 0.02f) drawRect(Color.White.copy(alpha = 0.9f), Offset(x, py - 3f), Size(w, 3f))
    }
}

private fun DrawScope.drawRadial(a: SpectrumAnalyzer, tint: Color) {
    val n = a.bands
    val c = center
    val r0 = min(size.width, size.height) * 0.36f
    val maxLen = min(size.width, size.height) * 0.14f
    val stroke = (2 * PI * r0 / (n * 2) * 0.55f).toFloat()
    // Symmetric: lows at the bottom, mirrored left/right like audioMotion's radial mode.
    for (side in 0..1) for (i in 0 until n) {
        val t = (i + 0.5f) / n
        val ang = (PI / 2 + (if (side == 0) 1 else -1) * t * PI).toFloat()
        val dx = cos(ang); val dy = sin(ang)
        val len = 2f + a.levels[i] * maxLen
        val color = lerp(tint, Color.White, a.levels[i] * 0.6f)
        drawLine(color, Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)), stroke, StrokeCap.Round)
        val pr = r0 + 2f + a.peaks[i] * maxLen + stroke
        if (a.peaks[i] > 0.02f) drawCircle(Color.White.copy(alpha = 0.85f), stroke * 0.45f, Offset(c.x + dx * pr, c.y + dy * pr))
    }
}
