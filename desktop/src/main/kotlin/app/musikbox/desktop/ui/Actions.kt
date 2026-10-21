package app.musikbox.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.PlaylistItem
import app.musikbox.desktop.data.Song
import app.musikbox.ui.components.songsLabel
import kotlinx.coroutines.launch
import app.musikbox.data.DownloadRules
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.autoReason

/** Builds right-click / ⋯ menu entries for songs; hosts the add-to-playlist dialog. */
class SongMenu(val items: (Song) -> List<ContextMenuItem>)

@Composable
fun rememberSongMenu(onRemove: ((Song) -> Unit)? = null): SongMenu {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    var addTarget by remember { mutableStateOf<List<Song>?>(null) }
    addTarget?.let { AddToPlaylistDialog(it) { addTarget = null } }
    return remember(onRemove) {
        SongMenu { song ->
            buildList {
                add(ContextMenuItem("Play next") {
                    c.player.playNext(song)
                    c.scope.launch { snackbar.showSnackbar("Playing next: ${song.title}") }
                })
                add(ContextMenuItem("Add to queue") {
                    c.player.enqueue(song)
                    c.scope.launch { snackbar.showSnackbar("Added to queue") }
                })
                add(ContextMenuItem("Add to playlist…") { addTarget = listOf(song) })
                add(ContextMenuItem(if (song.starred) "Unstar" else "Star") {
                    c.scope.launch { c.library.toggleStar(song).onFailure { snackbar.showSnackbar("Couldn't reach the server") } }
                })
                onRemove?.let { remove -> add(ContextMenuItem("Remove from this playlist") { remove(song) }) }
            }
        }
    }
}

/** Play / Shuffle / Download / Save / Open for a playlist. */
@Composable
fun rememberPlaylistMenu(): (PlaylistItem) -> List<ContextMenuItem> {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val settings by c.settings.state.collectAsState()
    val rules = DownloadRules(settings.autoDownloadAll, settings.autoDownloadSaved, settings.autoDownloadLiked)
    return remember(rules) {
        { item ->
            buildList {
                add(ContextMenuItem("Play") { c.player.playPlaylist(item.id, shuffle = false) })
                add(ContextMenuItem("Shuffle play") { c.player.playPlaylist(item.id, shuffle = true) })
                when {
                    autoReason(item.id, item.isFavorite, rules) != null -> Unit // kept by a download rule
                    item.offline -> add(ContextMenuItem("Remove download") {
                        c.library.setOffline(item.id, false)
                        c.scope.launch { snackbar.showSnackbar("Removed ${item.name} from downloads") }
                    })
                    else -> add(ContextMenuItem("Download") {
                        c.library.setOffline(item.id, true)
                        c.scope.launch { snackbar.showSnackbar("Downloading ${item.name}") }
                    })
                }
                if (item.id != LIKED_PLAYLIST_ID) {
                    add(ContextMenuItem(if (item.isFavorite) "Remove from saved" else "Save playlist") {
                        c.library.toggleFavorite(item.id)
                    })
                }
                add(ContextMenuItem("Open") { nav.go(Screen.Playlist(item.id)) })
            }
        }
    }
}

/** Renders context-menu entries as a dropdown (for ⋯ buttons). */
@Composable
fun MenuDropdown(expanded: Boolean, onDismiss: () -> Unit, items: List<ContextMenuItem>) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(10.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        items.forEach { item ->
            DropdownMenuItem(text = { Text(item.label, style = MaterialTheme.typography.bodyMedium) }, onClick = { onDismiss(); item.onClick() })
        }
    }
}

@Composable
fun NameDialog(title: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Playlist name") },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.width(360.dp).focusRequester(focus).tracksTextFocus(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(380.dp)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val playlists by c.library.playlists.collectAsState()
    val me = c.settings.current.username
    val editable = playlists.filter { it.playlist.owner == null || it.playlist.owner == me }
    var creating by remember { mutableStateOf(false) }
    val ids = songs.map { it.id }

    if (creating) {
        NameDialog("New playlist", "Create", onDismiss = { creating = false }) { name ->
            onDismiss()
            c.scope.launch {
                val r = c.library.createPlaylist(name, ids)
                snackbar.showSnackbar(if (r.isSuccess) "Created $name" else "Couldn't create playlist")
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column {
                Text("Add to playlist")
                Text(
                    if (songs.size == 1) songs.first().title else songsLabel(songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            LazyColumn(Modifier.width(380.dp).heightIn(max = 420.dp)) {
                item {
                    DialogRow(onClick = { creating = true }) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) }
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(editable, key = { it.id }) { p ->
                    DialogRow(onClick = {
                        onDismiss()
                        c.scope.launch {
                            val r = c.library.addToPlaylist(p.id, ids)
                            snackbar.showSnackbar(if (r.isSuccess) "Added to ${p.name}" else "Couldn't update playlist")
                        }
                    }) {
                        CoverArt(p.playlist.coverArt, Modifier.size(40.dp), sizePx = 120, seed = p.id)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DialogRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
