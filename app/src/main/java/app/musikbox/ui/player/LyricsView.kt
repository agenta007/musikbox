package app.musikbox.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.data.local.SongEntity
import app.musikbox.data.local.lyricsQuery
import app.musikbox.lyrics.Lyrics
import app.musikbox.lyrics.LyricsResult
import app.musikbox.lyrics.LyricsSource
import app.musikbox.ui.LocalAppContainer

/** Lead so a line lights up as it starts being sung, not after the next position poll. */
private const val LEAD_MS = 200L

/**
 * Lyrics for the playing song: synced lines follow playback (tap one to jump there),
 * plain lyrics simply scroll. Loaded from the user's server, then LRCLIB if enabled.
 */
@Composable
fun LyricsView(song: SongEntity, positionMs: Long, tint: Color, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var attempt by remember(song.id) { mutableIntStateOf(0) }
    val result by produceState<LyricsResult?>(null, song.id, attempt, settings.lyricsOnline, settings.lyricsServer) {
        value = null
        value = container.lyrics.load(song.lyricsQuery(), refresh = attempt > 0)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val r = result) {
            null -> CircularProgressIndicator(Modifier.size(28.dp), color = tint, strokeWidth = 2.5.dp)
            is LyricsResult.Failed -> Message("Couldn't load lyrics", r.message) {
                TextButton(onClick = { attempt++ }) { Text("Try again", color = tint) }
            }
            LyricsResult.None -> Message(
                "No lyrics for this song",
                if (settings.lyricsOnline) "Neither your server nor LRCLIB has them."
                else "Add .lrc files next to your music on the server, or turn on LRCLIB in Settings → Lyrics.",
            ) {
                TextButton(onClick = { attempt++ }) { Text("Look again", color = tint) }
            }
            is LyricsResult.Found ->
                if (r.lyrics.instrumental) Message("♪  Instrumental", null)
                else LyricLines(r.lyrics, positionMs, tint, onSeek)
        }
    }
}

@Composable
private fun Message(title: String, detail: String?, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
private fun LyricLines(lyrics: Lyrics, positionMs: Long, tint: Color, onSeek: (Long) -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastDragAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) lastDragAt = System.currentTimeMillis() }
    val current = lyrics.lineAt(positionMs + LEAD_MS)
    val anchor = maxHeight * 0.33f

    // The top padding parks the sung line a third of the way down; pause while the user reads elsewhere.
    LaunchedEffect(current) {
        if (current < 0 || dragged || System.currentTimeMillis() - lastDragAt < 4_000) return@LaunchedEffect
        listState.animateScrollToItem(current)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = if (lyrics.synced) PaddingValues(top = anchor, bottom = maxHeight * 0.5f) else PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (lyrics.synced) 14.dp else 6.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val active = lyrics.synced && i == current
            val color by animateColorAsState(
                when {
                    !lyrics.synced -> Color.White.copy(alpha = 0.88f)
                    active -> lerp(Color.White, tint, 0.3f)
                    i < current -> Color.White.copy(alpha = 0.32f)
                    else -> Color.White.copy(alpha = 0.5f)
                },
                label = "lyricColor",
            )
            val start = line.startMs
            Text(
                line.text.ifBlank { if (lyrics.synced) "♪" else "" },
                style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = if (lyrics.synced) FontWeight.Bold else FontWeight.Medium,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (lyrics.synced && start != null) Modifier.clickable { onSeek(start) } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        item {
            Text(
                if (lyrics.source == LyricsSource.Server) "Lyrics from your server" else "Lyrics from LRCLIB",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 24.dp, start = 6.dp),
            )
        }
    }
}
