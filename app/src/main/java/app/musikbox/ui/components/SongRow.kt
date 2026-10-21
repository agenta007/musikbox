package app.musikbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.data.local.SongEntity

data class SongActions(
    val onPlayNext: (SongEntity) -> Unit,
    val onEnqueue: (SongEntity) -> Unit,
    val onAddToPlaylist: (SongEntity) -> Unit,
    val onToggleStar: (SongEntity) -> Unit,
    /** Non-null only where the song can be removed (e.g. an editable playlist). */
    val onRemove: ((SongEntity) -> Unit)? = null,
    val removeLabel: String = "Remove from playlist",
)

@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
    index: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloaded: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Equalizer(accent, isPlaying)
                } else {
                    Text(
                        "$index",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        CoverArt(song.coverArt, Modifier.size(46.dp), sizePx = 120, shape = RoundedCornerShape(6.dp), iconSize = 18.dp, seed = song.albumId)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.Rounded.DownloadDone, "Available offline",
                        tint = accent, modifier = Modifier.size(14.dp).padding(end = 3.dp),
                    )
                }
                Text(
                    listOfNotNull(song.artist, song.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatTrackTime(song.durationSec * 1000L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        SongMenu(song, actions)
    }
}

@Composable
fun SongMenu(song: SongEntity, actions: SongActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            MenuItem("Play next", Icons.Rounded.SkipNext) { open = false; actions.onPlayNext(song) }
            MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { open = false; actions.onEnqueue(song) }
            MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; actions.onAddToPlaylist(song) }
            MenuItem(
                if (song.starred) "Unstar" else "Star",
                if (song.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            ) { open = false; actions.onToggleStar(song) }
            actions.onRemove?.let { remove ->
                MenuItem(actions.removeLabel, Icons.Rounded.DeleteOutline) { open = false; remove(song) }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}
