package app.musikbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.data.local.PlaylistItem
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalSnackbar
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.autoReason
import app.musikbox.data.inDownloadList
import app.musikbox.data.settings.downloadRules

/** Bottom sheet of quick actions for a playlist: play, shuffle, download, favourite, open. */
@Composable
fun PlaylistActionsSheet(item: PlaylistItem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val library = container.library

    fun act(block: suspend () -> Unit) {
        onDismiss()
        container.scope.launch { block() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(item.playlist.coverArt, Modifier.size(56.dp), sizePx = 160, shape = RoundedCornerShape(8.dp), seed = item.id)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        songsLabel(item.playlist.songCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            SheetRow(Icons.Rounded.PlayArrow, "Play") { act { container.player.playPlaylist(item.id, shuffle = false) } }
            SheetRow(Icons.Rounded.Shuffle, "Shuffle play") { act { container.player.playPlaylist(item.id, shuffle = true) } }
            val rules = settings.downloadRules
            val auto = autoReason(item.id, item.isFavorite, rules)
            when {
                auto != null -> SheetRow(Icons.Rounded.DownloadDone, "Downloaded automatically", subtitle = auto, onClick = null)
                item.offline -> SheetRow(Icons.Rounded.FileDownloadOff, "Remove download") {
                    act {
                        library.setOffline(item.id, false)
                        snackbar.showSnackbar("Removed ${item.name} from downloads")
                    }
                }
                else -> SheetRow(Icons.Rounded.Download, "Download", subtitle = "Listen without a connection") {
                    act {
                        library.setOffline(item.id, true)
                        snackbar.showSnackbar("Downloading ${item.name}")
                    }
                }
            }
            if (inDownloadList(item.id, item.offline, item.isFavorite, rules)) {
                SheetRow(Icons.Rounded.Refresh, "Re-download", subtitle = "Delete this playlist's files and fetch them again") {
                    act {
                        library.redownloadPlaylist(item.id)
                        snackbar.showSnackbar("Re-downloading ${item.name}")
                    }
                }
            }
            if (item.id != LIKED_PLAYLIST_ID) {
                SheetRow(
                    if (item.isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    if (item.isFavorite) "Remove from saved" else "Save playlist",
                ) { act { library.toggleFavorite(item.id) } }
            }
            SheetRow(Icons.AutoMirrored.Rounded.QueueMusic, "Open playlist") { onDismiss(); onOpen() }
        }
    }
}

@Composable
fun SheetRow(icon: ImageVector, label: String, subtitle: String? = null, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Hosts the actions sheet; returns a function that opens it for a playlist.
 * [onOpen] navigates to the playlist screen.
 */
@Composable
fun rememberPlaylistActions(onOpen: (String) -> Unit): (PlaylistItem) -> Unit {
    var target by remember { mutableStateOf<PlaylistItem?>(null) }
    target?.let { item ->
        PlaylistActionsSheet(item, onOpen = { onOpen(item.id) }, onDismiss = { target = null })
    }
    return remember { { item: PlaylistItem -> target = item } }
}
