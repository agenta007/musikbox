package app.musikbox.desktop.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun placeholderBrush(seed: String?): Brush {
    val hue = abs((seed ?: "").hashCode() % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsl(hue, 0.45f, 0.30f), Color.hsl((hue + 40f) % 360f, 0.55f, 0.16f)))
}

@Composable
fun CoverArt(
    coverArt: String?,
    modifier: Modifier = Modifier,
    sizePx: Int = 300,
    shape: Shape = RoundedCornerShape(6.dp),
    iconSize: Dp = 20.dp,
    seed: String? = coverArt,
) {
    val url = LocalContainer.current.library.coverUrl(coverArt, sizePx)
    Box(modifier.clip(shape).background(placeholderBrush(seed)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private val colorCache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 128
}

/** Vibrant accent pulled from artwork, animated, clamped to read well on the dark UI. */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color): State<Color> {
    val anim = remember { Animatable(fallback) }
    LaunchedEffect(url) {
        val target = url?.let { extractColor(it) } ?: fallback
        anim.animateTo(target, tween(700))
    }
    return anim.asState()
}

private suspend fun extractColor(url: String): Color? {
    synchronized(colorCache) { colorCache[url] }?.let { return it }
    val ctx = PlatformContext.INSTANCE
    val result = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).size(96).build())
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    val color = withContext(Dispatchers.Default) {
        // Score sampled pixels by saturation × mid-lightness; average the best bucket's hue.
        val buckets = HashMap<Int, FloatArray>() // hueBucket -> [score, h, s, l, n]
        val step = max(1, min(bitmap.width, bitmap.height) / 24)
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val argb = bitmap.getColor(x, y)
            val (h, s, l) = rgbToHsl(argb)
            if (l < 0.12f || l > 0.92f) continue
            val score = s * (1f - abs(l - 0.5f) * 1.6f)
            val b = buckets.getOrPut((h / 30f).toInt()) { FloatArray(5) }
            b[0] += score; b[1] += h; b[2] += s; b[3] += l; b[4] += 1f
        }
        val best = buckets.values.maxByOrNull { it[0] } ?: return@withContext null
        val n = best[4]
        Color.hsl(best[1] / n, (best[2] / n).coerceIn(0.35f, 0.85f), (best[3] / n).coerceIn(0.42f, 0.62f))
    } ?: return null
    synchronized(colorCache) { colorCache[url] = color }
    return color
}

private fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0))
        g -> ((b - r) / d + 2)
        else -> ((r - g) / d + 4)
    } * 60f
    return Triple(h, s, l)
}

@Composable
fun Equalizer(color: Color, playing: Boolean, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = intArrayOf(520, 680, 450).mapIndexed { i, d ->
        t.animateFloat(0.25f + i * 0.15f, 1f, infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse), label = "bar$i")
    }
    val still = floatArrayOf(0.55f, 0.9f, 0.4f)
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.14f
        val w = (this.size.width - gap * 2) / 3f
        for (i in 0..2) {
            val h = this.size.height * (if (playing) bars[i].value else still[i])
            drawRoundRect(color, Offset(i * (w + gap), this.size.height - h), Size(w, h), CornerRadius(w / 2.5f))
        }
    }
}

/** Thin scrubber: click or drag anywhere; thickens on hover. */
@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    enabled: Boolean = true,
    onDragFraction: (Float?) -> Unit = {},
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val dragCb by rememberUpdatedState(onDragFraction)
    val shown = drag ?: fraction.coerceIn(0f, 1f)
    val thickness by animateDpAsState(if (hovered || drag != null) 5.dp else 3.dp)
    val active = hovered || drag != null
    Canvas(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f); dragCb(drag) },
                            onDragEnd = { drag?.let(seek); drag = null; dragCb(null) },
                            onDragCancel = { drag = null; dragCb(null) },
                        ) { change, _ ->
                            change.consume()
                            drag = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragCb(drag)
                        }
                    },
            ),
    ) {
        val y = size.height / 2
        val stroke = thickness.toPx()
        drawLine(Color.White.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(if (active) color else Color.White.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width * shown, y), stroke, StrokeCap.Round)
        if (active) drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * shown, y))
    }
}

fun tintFade(color: Color, bottom: Color, strength: Float = 0.5f) =
    Brush.verticalGradient(listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.3f), bottom))
