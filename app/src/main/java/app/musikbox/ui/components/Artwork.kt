package app.musikbox.ui.components

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.musikbox.ui.LocalAppContainer
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Deterministic two-tone gradient used while art loads or when there is none. */
fun placeholderBrush(seed: String?): Brush {
    val hue = abs((seed ?: "").hashCode() % 360).toFloat()
    val a = Color.hsl(hue, 0.45f, 0.30f)
    val b = Color.hsl((hue + 40f) % 360f, 0.55f, 0.16f)
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun CoverArt(
    coverArt: String?,
    modifier: Modifier = Modifier,
    sizePx: Int = 300,
    shape: Shape = RoundedCornerShape(8.dp),
    iconSize: Dp = 22.dp,
    seed: String? = coverArt,
) {
    val url = LocalAppContainer.current.library.coverUrl(coverArt, sizePx)
    Box(modifier.clip(shape).background(placeholderBrush(seed)), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val colorCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
}

/**
 * Extracts an accent from artwork and animates toward it. The swatch is nudged into a
 * range that reads well as a tint over the near-black background.
 */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color): State<Color> {
    val context = LocalContext.current
    val anim = remember { Animatable(fallback) }
    LaunchedEffect(url) {
        val target = url?.let { extractColor(context, it) }?.let { Color(it) } ?: fallback
        anim.animateTo(target, tween(700))
    }
    return anim.asState()
}

private suspend fun extractColor(context: Context, url: String): Int? {
    synchronized(colorCache) { colorCache[url] }?.let { return it }
    val request = ImageRequest.Builder(context).data(url).size(160).allowHardware(false).build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    val color = withContext(Dispatchers.Default) {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch
            ?: return@withContext null
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        hsl[1] = hsl[1].coerceIn(0.35f, 0.85f)
        hsl[2] = hsl[2].coerceIn(0.42f, 0.62f)
        ColorUtils.HSLToColor(hsl)
    } ?: return null
    synchronized(colorCache) { colorCache[url] = color }
    return color
}

