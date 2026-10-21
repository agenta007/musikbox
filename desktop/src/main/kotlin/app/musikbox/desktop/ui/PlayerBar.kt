package app.musikbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.player.Repeat
import app.musikbox.desktop.player.SleepTimer
import app.musikbox.ui.components.formatTrackTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerBar(queueOpen: Boolean, onToggleQueue: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val player = c.player
    val state by player.state.collectAsState()
    val position by rememberPlayhead()
    val song = state.song
    val tint by rememberArtworkColor(c.library.coverUrl(song?.coverArt, 300), MaterialTheme.colorScheme.primary)
    var addTarget by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Now playing
            Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    CoverArt(
                        song.coverArt,
                        Modifier.size(56.dp).clickable { nav.go(Screen.NowPlaying) }.pointerHoverIcon(PointerIcon.Hand),
                        sizePx = 160, seed = song.albumId,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { nav.go(Screen.NowPlaying) }.pointerHoverIcon(PointerIcon.Hand),
                        )
                        Text(
                            song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StarButton(song.id, song.starred)
                    IconButton(onClick = { addTarget = true }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Transport + seek
            Column(Modifier.weight(0.4f).widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle(Icons.Rounded.Shuffle, "Shuffle", state.shuffle, tint, player::toggleShuffle)
                    IconButton(onClick = player::previous, enabled = state.hasPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(28.dp)) }
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Color.White)
                            .clickable(enabled = song != null, onClick = player::togglePlay).pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.buffering) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    IconButton(onClick = player::next, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(28.dp)) }
                    Toggle(
                        if (state.repeat == Repeat.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat", state.repeat != Repeat.Off, tint, player::cycleRepeat,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dur = state.durationMs.coerceAtLeast(1)
                    val shownPos = dragFraction?.let { (it * dur).toLong() } ?: position
                    Text(formatTrackTime(shownPos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                    SeekBar(
                        fraction = position.toFloat() / dur,
                        onSeek = { player.seekTo((it * dur).toLong()) },
                        color = tint,
                        enabled = song != null,
                        onDragFraction = { dragFraction = it },
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatTrackTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp).padding(start = 10.dp))
                }
            }

            // Extras
            Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SleepButton(tint)
                Toggle(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", queueOpen, tint, onToggleQueue)
                val muted = state.volume == 0
                var lastVolume by remember { mutableStateOf(80) }
                IconButton(onClick = {
                    if (muted) player.setVolume(lastVolume) else { lastVolume = state.volume; player.setVolume(0) }
                }) {
                    Icon(
                        when {
                            muted -> Icons.AutoMirrored.Rounded.VolumeOff
                            state.volume < 50 -> Icons.AutoMirrored.Rounded.VolumeDown
                            else -> Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                    )
                }
                SeekBar(
                    fraction = state.volume / 100f,
                    onSeek = { player.setVolume((it * 100).toInt()) },
                    onDragFraction = { f -> f?.let { player.setVolume((it * 100).toInt()) } },
                    color = tint,
                    modifier = Modifier.width(110.dp),
                )
                val onNowPlaying = nav.current == Screen.NowPlaying
                IconButton(onClick = { if (onNowPlaying) nav.back() else nav.go(Screen.NowPlaying) }, enabled = song != null) {
                    Icon(
                        if (onNowPlaying) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        "Now playing", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
    if (addTarget && song != null) AddToPlaylistDialog(listOf(song)) { addTarget = false }
}

@Composable
fun StarButton(songId: String, fallback: Boolean) {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val stored by remember(songId) { c.library.song(songId) }.collectAsState(null)
    var override by remember(songId) { mutableStateOf<Boolean?>(null) }
    val starred = override ?: stored?.starred ?: fallback
    IconButton(onClick = {
        val s = (stored ?: c.player.state.value.song ?: return@IconButton).copy(starred = starred)
        override = !starred
        c.scope.launch {
            c.library.toggleStar(s).onFailure { override = starred; snackbar.showSnackbar("Couldn't reach the server") }
        }
    }) {
        Icon(
            if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            if (starred) "Unstar" else "Star",
            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            if (active) Box(Modifier.align(Alignment.BottomCenter).padding(top = 26.dp).size(4.dp).clip(CircleShape).background(tint))
        }
    }
}

@Composable
private fun SleepButton(tint: Color) {
    val player = LocalContainer.current.player
    val timer by player.sleepTimer.collectAsState()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer is SleepTimer.At) { now = System.currentTimeMillis(); delay(1000) }
    }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bedtime, "Sleep timer", tint = if (timer != null) tint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            when (val t = timer) {
                is SleepTimer.At -> Text(" " + formatTrackTime(t.endsAtMs - now), color = tint, style = MaterialTheme.typography.labelMedium)
                SleepTimer.EndOfTrack -> Text(" end of track", color = tint, style = MaterialTheme.typography.labelMedium)
                null -> Unit
            }
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutes") }, onClick = { open = false; player.setSleepTimer(m) })
            }
            DropdownMenuItem(text = { Text("End of track") }, onClick = { open = false; player.sleepAtEndOfTrack() })
            if (timer != null) {
                DropdownMenuItem(text = { Text("Turn off", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; player.cancelSleepTimer() })
            }
        }
    }
}

