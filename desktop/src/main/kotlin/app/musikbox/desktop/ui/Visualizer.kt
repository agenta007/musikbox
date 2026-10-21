package app.musikbox.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import app.musikbox.visualizer.SpectrumAnalyzer
import app.musikbox.visualizer.VisualizerStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Feishin/audioMotion-style spectrum. Samples come from a decoded copy of the track (see
 * TrackPcm), indexed by mpv's playhead, interpolated between position updates for 60 fps.
 */
@Composable
fun SpectrumVisualizer(style: VisualizerStyle, tint: Color, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsState()
    val position by c.player.position.collectAsState()
    val pcm by c.trackPcm.current.collectAsState()
    LaunchedEffect(state.song?.id) { c.trackPcm.ensure(state.song?.id) }

    val analyzer = remember(style) { SpectrumAnalyzer(bands = 64) }
    val buffer = remember(analyzer) { FloatArray(analyzer.windowSize) }
    // Anchor = last reported position and when we got it; extrapolate while playing.
    var anchorPos by remember { mutableLongStateOf(0L) }
    var anchorAt by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(position) { anchorPos = position; anchorAt = System.nanoTime() }
    val playing by rememberUpdatedState(state.isPlaying)
    val currentPcm by rememberUpdatedState(pcm?.takeIf { it.songId == state.song?.id })
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(analyzer) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(1f, 100f)
                last = now
                val p = currentPcm
                val pos = anchorPos + if (playing) (now - anchorAt) / 1_000_000 else 0
                val ok = playing && p != null && c.trackPcm.window(p, pos, buffer)
                analyzer.update(if (ok) buffer else null, p?.rate ?: 22_050, dt)
                frame++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
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
            drawRoundRect(brush, Offset(x, base - h), Size(w, h), CornerRadius(w / 4))
            if (mirror) drawRoundRect(tint.copy(alpha = 0.35f), Offset(x, base), Size(w, h * 0.85f), CornerRadius(w / 4))
        }
        if (a.peaks[i] > 0.02f) drawRect(Color.White.copy(alpha = 0.9f), Offset(x, base - a.peaks[i] * maxH - 3f), Size(w, 3f))
    }
}

private fun DrawScope.drawRadial(a: SpectrumAnalyzer, tint: Color) {
    val n = a.bands
    val c = center
    val r0 = min(size.width, size.height) * 0.36f
    val maxLen = min(size.width, size.height) * 0.13f
    val stroke = (2 * PI * r0 / (n * 2) * 0.55f).toFloat()
    for (side in 0..1) for (i in 0 until n) {
        val t = (i + 0.5f) / n
        val ang = (PI / 2 + (if (side == 0) 1 else -1) * t * PI).toFloat()
        val dx = cos(ang); val dy = sin(ang)
        val len = 2f + a.levels[i] * maxLen
        drawLine(lerp(tint, Color.White, a.levels[i] * 0.6f), Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)), stroke, StrokeCap.Round)
        val pr = r0 + 2f + a.peaks[i] * maxLen + stroke
        if (a.peaks[i] > 0.02f) drawCircle(Color.White.copy(alpha = 0.85f), stroke * 0.45f, Offset(c.x + dx * pr, c.y + dy * pr))
    }
}
