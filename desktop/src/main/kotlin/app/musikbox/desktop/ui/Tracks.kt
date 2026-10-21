package app.musikbox.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.Song
import app.musikbox.ui.components.formatTrackTime

private val NumberWidth = 40.dp
private val TimeWidth = 64.dp

@Composable
fun TrackHeader(modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(NumberWidth))
            Spacer(Modifier.width(12.dp))
            Text("TITLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(2f).padding(start = 52.dp))
            Text("ALBUM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
            Box(Modifier.width(TimeWidth + 64.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Rounded.Schedule, "Duration", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp).padding(start = 0.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Desktop track row: double-click plays, hover reveals ▶ and ⋯, right-click opens the
 * song menu (play next, queue, add to playlist, star, remove).
 */
@Composable
fun TrackRow(
    song: Song,
    index: Int?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    downloaded: Boolean,
    onPlay: () -> Unit,
    menuItems: () -> List<ContextMenuItem>,
    modifier: Modifier = Modifier,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    ContextMenuArea(items = menuItems) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered || menuOpen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .hoverable(hover)
                .combinedClickable(onClick = {}, onDoubleClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(NumberWidth), contentAlignment = Alignment.Center) {
                when {
                    hovered -> IconButton(onClick = onPlay, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(20.dp))
                    }
                    isCurrent -> Equalizer(accent, isPlaying)
                    index != null -> Text("$index", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(song.coverArt, Modifier.size(40.dp), sizePx = 120, seed = song.albumId, iconSize = 16.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (downloaded) Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = accent, modifier = Modifier.size(13.dp).padding(end = 3.dp))
                        Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                song.album.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f).padding(end = 12.dp),
            )
            Box(Modifier.width(24.dp)) {
                if (song.starred) Icon(Icons.Rounded.Star, "Starred", tint = accent, modifier = Modifier.size(15.dp))
            }
            Text(
                formatTrackTime(song.durationSec * 1000L), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(TimeWidth),
            )
            Box(Modifier.width(40.dp)) {
                if (hovered || menuOpen) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, "More", modifier = Modifier.size(18.dp))
                    }
                }
                MenuDropdown(menuOpen, { menuOpen = false }, if (menuOpen) menuItems() else emptyList())
            }
        }
    }
}

