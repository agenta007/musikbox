package app.musikbox.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.player.QItem
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.formatTrackTime
import coil3.compose.AsyncImage
import app.musikbox.visualizer.VisualizerStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.rounded.GraphicEq

@Composable
fun NowPlayingScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val state by c.player.state.collectAsState()
    val song = state.song
    if (song == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Text("Nothing playing", style = MaterialTheme.typography.titleLarge)
            Text("Pick a playlist to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val art = c.library.coverUrl(song.coverArt, 1000)
    val tint by rememberArtworkColor(c.library.coverUrl(song.coverArt, 300), MaterialTheme.colorScheme.primary)
    val bg = MaterialTheme.colorScheme.background
    val source by remember(state.sourcePlaylistId) {
        state.sourcePlaylistId?.let { c.library.playlist(it) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(null)
    var addTarget by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.9f, spring(dampingRatio = 0.6f, stiffness = 300f))
    val settings by c.settings.state.collectAsState()
    val viz = VisualizerStyle.entries.firstOrNull { it.name == settings.visualizerStyle } ?: VisualizerStyle.Bars
    val radial = viz == VisualizerStyle.Radial

    Box(Modifier.fillMaxSize()) {
        AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(120.dp).graphicsLayer { alpha = 0.45f })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.35f), bg.copy(alpha = 0.75f), bg.copy(alpha = 0.95f)))))
        if (viz == VisualizerStyle.Bars || viz == VisualizerStyle.Mirror) {
            // Feishin-style: a full-width spectrum along the bottom, behind the content.
            SpectrumVisualizer(
                viz, tint,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (viz == VisualizerStyle.Mirror) 0.42f else 0.3f)
                    .graphicsLayer { alpha = 0.85f },
            )
        }
        Row(Modifier.fillMaxSize().padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (radial) SpectrumVisualizer(viz, tint, Modifier.widthIn(max = 760.dp).aspectRatio(1f, matchHeightConstraintsFirst = true))
                Crossfade(song.id) {
                    CoverArt(
                        song.coverArt,
                        Modifier.widthIn(max = 560.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .graphicsLayer { val k = if (radial) scale * 0.6f else scale; scaleX = k; scaleY = k }
                            .shadow(40.dp, if (radial) CircleShape else RoundedCornerShape(16.dp), ambientColor = tint, spotColor = tint),
                        sizePx = 1000, shape = if (radial) CircleShape else RoundedCornerShape(16.dp), iconSize = 80.dp, seed = song.albumId,
                    )
                }
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                if (source != null) {
                    Text("PLAYING FROM PLAYLIST", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    Text(
                        source!!.name, style = MaterialTheme.typography.titleMedium, color = tint,
                        modifier = Modifier.clickable { nav.go(Screen.Playlist(source!!.id)) }.pointerHoverIcon(PointerIcon.Hand),
                    )
                    Spacer(Modifier.height(18.dp))
                }
                Text(song.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(song.artist, song.album).joinToString(" — "),
                    style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.72f), maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(song.year?.toString(), song.genre, song.suffix?.uppercase(), song.bitRate?.let { "$it kbps" }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarButton(song.id, song.starred)
                    IconButton(onClick = { addTarget = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
                    Spacer(Modifier.width(8.dp))
                    VisualizerStyle.entries.forEach { st ->
                        FilterChip(
                            selected = viz == st,
                            onClick = { c.settings.update { it.copy(visualizerStyle = st.name) } },
                            label = { Text(st.label) },
                            leadingIcon = if (st == VisualizerStyle.Bars) ({ Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)) }) else null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                val upNext = state.queue.drop(state.pos + 1)
                Text("Up next", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (upNext.isEmpty()) {
                    Text("End of queue", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.2f)).padding(6.dp)) {
                        upNext.take(6).forEach { QueueRow(it, current = false, playing = false) }
                        if (upNext.size > 6) {
                            Text(
                                "+ ${upNext.size - 6} more · ${formatLongDuration(upNext.drop(6).sumOf { it.song.durationSec.toLong() })}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (addTarget) AddToPlaylistDialog(listOf(song)) { addTarget = false }
}

@Composable
fun QueueRow(item: QItem, current: Boolean, playing: Boolean) {
    val player = LocalContainer.current.player
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (current || hovered) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f) else Color.Transparent)
            .hoverable(hover).clickable { player.skipTo(item.uid) }.pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(item.song.coverArt, Modifier.size(38.dp), sizePx = 100, seed = item.song.albumId, iconSize = 14.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(item.song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            current -> Equalizer(MaterialTheme.colorScheme.primary, playing, Modifier.padding(horizontal = 8.dp))
            hovered -> IconButton(onClick = { player.remove(item.uid) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, "Remove from queue", modifier = Modifier.size(16.dp))
            }
            else -> Text(formatTrackTime(item.song.durationSec * 1000L), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Right-hand queue drawer toggled from the player bar. */
@Composable
fun QueuePanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalContainer.current.player
    val state by player.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.current?.uid) { if (state.pos > 0) listState.animateScrollToItem((state.pos - 1).coerceAtLeast(0)) }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.queue.isNotEmpty()) TextButton(onClick = player::clear) { Text("Clear") }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close queue") }
        }
        val remaining = state.queue.drop(state.pos + 1)
        Text(
            "${remaining.size} up next · ${formatLongDuration(remaining.sumOf { it.song.durationSec.toLong() })}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                itemsIndexed(state.queue, key = { _, it -> it.uid }) { i, item ->
                    QueueRow(item, current = i == state.pos, playing = state.isPlaying)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
