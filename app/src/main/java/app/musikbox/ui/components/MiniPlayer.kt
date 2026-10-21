package app.musikbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

val MiniPlayerHeight = 64.dp
val MiniPlayerInset = MiniPlayerHeight + 16.dp

/** Floating now-playing pill. Tap to expand, swipe sideways to skip. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalAppContainer.current.player
    val state by player.state.collectAsStateWithLifecycle()
    val song = state.song ?: return
    val tint by rememberArtworkColor(state.artworkUrl, Palette.Surface3)
    val container = lerp(tint, Color(0xFF15151C), 0.68f)

    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying, song.id, state.durationMs) {
        while (true) {
            val d = state.durationMs
            progress = if (d > 0) (player.positionMs.toFloat() / d).coerceIn(0f, 1f) else 0f
            if (!state.isPlaying) break
            delay(500)
        }
    }

    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val dragAnim by animateFloatAsState(drag, label = "miniDrag")

    Box(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = tint, spotColor = tint)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -threshold) player.next() else if (drag > threshold) player.previous()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .offset { IntOffset((dragAnim * 0.4f).roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(dragAnim) / (threshold * 3)).coerceIn(0f, 0.5f) }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(song.coverArt, Modifier.size(48.dp), sizePx = 160, shape = RoundedCornerShape(10.dp), seed = song.albumId)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlayPause) {
                if (state.isBuffering && state.isPlaying.not()) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(onClick = player::next, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White.copy(alpha = if (state.hasNext) 1f else 0.35f))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
        }
    }
}
