package app.musikbox.ui.radio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musikbox.radio.RadioCatalog
import app.musikbox.radio.RadioDial
import app.musikbox.radio.RadioStation
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.components.placeholderBrush
import app.musikbox.ui.theme.Palette
import coil.compose.SubcomposeAsyncImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** "🇧🇬 Bulgaria · Pop · MP3 128k" */
fun RadioCatalog.subtitle(s: RadioStation, withCountry: Boolean = true): String = listOfNotNull(
    if (withCountry) "${s.countryCode.let(::flag)} ${countryName(s.countryCode)}" else null,
    s.genres.firstOrNull(),
    s.quality.takeIf { it.isNotBlank() },
).joinToString(" · ")

private fun flag(code: String) = app.musikbox.radio.flagOf(code)

/**
 * The station's logo, or (no logo, or it won't load) its initials on a colour of its own.
 * Logos are small favicons on all sorts of backgrounds, so they sit on a light tile, inset.
 */
@Composable
fun StationLogo(name: String, logo: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp), textSize: Int = 16) {
    val fallback = @Composable {
        Box(Modifier.fillMaxSize().background(placeholderBrush(name)), contentAlignment = Alignment.Center) {
            Text(initials(name), color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = textSize.sp)
        }
    }
    Box(modifier.clip(shape)) {
        if (logo == null) fallback() else SubcomposeAsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { fallback() },
            error = { fallback() },
            success = { state ->
                Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F5)).padding(6.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(state.painter, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun initials(name: String): String {
    val words = name.split(' ', '-', '_', '.').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "FM" }
}

@Composable
fun FavoriteButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.White) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (on) "Remove from favourites" else "Add to favourites",
            tint = if (on) Palette.Accent else tint.copy(alpha = 0.8f),
        )
    }
}

/**
 * The station's live waveform, from the same PCM tap as the visualizer: an oscilloscope trace
 * with a soft glow. [static] (0..1) blends in tuning noise while the dial moves between stations;
 * when nothing plays the line rests, breathing slightly.
 */
@Composable
fun LiveWave(playing: Boolean, tint: Color, modifier: Modifier = Modifier, static: Float = 0f, live: Boolean = true) {
    val tap = LocalAppContainer.current.visualizerTap
    val samples = remember { FloatArray(1024) }
    val shown = remember { FloatArray(160) }
    val isPlaying by rememberUpdatedState(playing && live)
    val noise by rememberUpdatedState(static)
    var frame by remember { mutableIntStateOf(0) }
    val level by animateFloatAsState(if (playing && live) 1f else 0.15f, label = "waveLevel")

    if (live) DisposableEffect(Unit) {
        tap.acquire()
        onDispose { tap.release() }
    }
    LaunchedEffect(live) {
        var t = 0f
        while (true) {
            withFrameNanos {
                t += 0.016f
                val fresh = isPlaying && tap.read(samples)
                val step = samples.size / shown.size
                for (i in shown.indices) {
                    // the sample of largest magnitude in each slice keeps the trace lively at any width
                    var v = 0f
                    if (fresh) for (j in i * step until (i + 1) * step) if (abs(samples[j]) > abs(v)) v = samples[j]
                    val rest = 0.04f * sin(t * 2.2f + i * 0.18f)
                    val target = (if (fresh) v * 1.6f else rest) * (1 - noise) + (Random.nextFloat() * 2 - 1) * 0.55f * noise
                    shown[i] += (target - shown[i]) * 0.55f
                }
                frame++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val mid = size.height / 2
        val amp = size.height / 2 * 0.92f * max(level, noise)
        val path = Path()
        for (i in shown.indices) {
            val x = size.width * i / (shown.size - 1)
            val y = mid - shown[i].coerceIn(-1f, 1f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val brush = Brush.horizontalGradient(listOf(tint.copy(alpha = 0f), lerp(tint, Color.White, 0.4f), Color.White, lerp(tint, Color.White, 0.4f), tint.copy(alpha = 0f)))
        drawPath(path, brush, alpha = 0.25f, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, brush, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * An FM dial: a tick per station, frequencies under some of them, a fixed red needle in the
 * middle. [position] is the pager's position in stations (fractional while swiping), so the
 * scale slides under the needle. Dragging the dial tunes: [onDrag] gets the distance in
 * stations, [onDragEnd] asks to settle on the nearest.
 */
@Composable
fun TunerDial(
    position: Float,
    count: Int,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Accent,
    spacing: Dp = 22.dp,
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onTap: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.TextMid, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val pos by rememberUpdatedState(position)
    Canvas(
        modifier
            .pointerInput(count) {
                detectHorizontalDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, dx ->
                    change.consume()
                    onDrag(-dx / spacing.toPx())
                }
            }
            .pointerInput(count) {
                detectTapGestures { o ->
                    val target = (pos + (o.x - size.width / 2f) / spacing.toPx()).let { kotlin.math.round(it).toInt() }
                    onTap(target.coerceIn(0, count - 1))
                }
            },
    ) {
        val step = spacing.toPx()
        val cx = size.width / 2
        val base = size.height * 0.62f
        // the band's edges fade out
        val first = max(0, floor(pos - cx / step).toInt() - 1)
        val last = minOf(count - 1, (pos + cx / step).toInt() + 1)
        val labelEvery = max(1, (58.dp.toPx() / step).toInt() + 1)
        for (i in first..last) {
            val x = cx + (i - pos) * step
            val edge = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val major = i % labelEvery == 0
            val h = if (major) size.height * 0.34f else size.height * 0.18f
            drawLine(
                Palette.TextMid.copy(alpha = 0.25f + 0.55f * edge),
                Offset(x, base - h), Offset(x, base),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round,
            )
            if (major) {
                val text = measurer.measure(RadioDial.label(i, count), labelStyle.copy(color = labelStyle.color.copy(alpha = 0.3f + 0.7f * edge)))
                drawText(text, topLeft = Offset(x - text.size.width / 2f, base + 4.dp.toPx()))
            }
        }
        // the band line and the needle
        drawLine(Palette.TextLow.copy(alpha = 0.5f), Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        drawLine(tint.copy(alpha = 0.3f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** A list row: logo, name, where it is from and how it sounds, a heart. */
@Composable
fun StationRow(
    station: RadioStation,
    subtitle: String,
    favorite: Boolean,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            StationLogo(station.name, station.logo, Modifier.size(52.dp))
            if (current) Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { EqualizerBars(playing, Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) Palette.Accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FavoriteButton(favorite, onToggleFavorite, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three bouncing bars: this station is on. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) withFrameNanos { t = (it / 1e9f) }
    }
    Canvas(modifier) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val h = if (playing) (0.35f + 0.65f * abs(sin(t * (3.1f + i * 1.3f) + i * PI.toFloat() / 3))) else 0.3f
            drawRoundRect(
                color, topLeft = Offset(w * i * 2, size.height * (1 - h)),
                size = androidx.compose.ui.geometry.Size(w, size.height * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
}
