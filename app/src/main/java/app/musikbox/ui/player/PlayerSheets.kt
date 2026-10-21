package app.musikbox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.playback.SleepTimer
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.components.Equalizer
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.songsLabel

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val player = LocalAppContainer.current.player
    val state by player.state.collectAsStateWithLifecycle()
    val currentPos = state.queue.indexOfFirst { it.index == state.currentIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPos)
    val remaining = state.queue.drop(currentPos + 1)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxHeight(0.85f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "${songsLabel(remaining.size)} up next · ${formatLongDuration(remaining.sumOf { it.song.durationSec.toLong() })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState) {
                items(state.queue, key = { it.index }) { entry ->
                    val current = entry.index == state.currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (current) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                            .clickable { player.skipTo(entry.index) }
                            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                            .animateItem(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(entry.song.coverArt, Modifier.size(44.dp), sizePx = 120, shape = RoundedCornerShape(6.dp), seed = entry.song.albumId)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.song.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.song.artist.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Equalizer(MaterialTheme.colorScheme.primary, state.isPlaying, Modifier.padding(horizontal = 16.dp))
                        } else {
                            IconButton(onClick = { player.removeAt(entry.index) }) {
                                Icon(Icons.Rounded.Close, "Remove from queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val player = LocalAppContainer.current.player
    val timer by player.sleepTimer.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                SheetOption("$m minutes") { player.setSleepTimer(m); onDismiss() }
            }
            SheetOption("End of track", selected = timer == SleepTimer.EndOfTrack) { player.sleepAtEndOfTrack(); onDismiss() }
            if (timer != null) {
                SheetOption("Turn off timer", danger = true) { player.cancelSleepTimer(); onDismiss() }
            }
        }
    }
}

@Composable
private fun SheetOption(label: String, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = when {
            danger -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
