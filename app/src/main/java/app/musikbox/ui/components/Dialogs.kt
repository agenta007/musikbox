package app.musikbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.data.local.SongEntity
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalSnackbar
import kotlinx.coroutines.launch

@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick one of your own playlists (or make a new one) for [songs]. */
@Composable
fun AddToPlaylistSheet(songs: List<SongEntity>, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val playlists by container.library.playlists.collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val me = settings.credentials?.username
    // The liked list isn't a server playlist (star songs instead); `playlists` already omits it.
    val editable = playlists.filter { it.playlist.owner == null || it.playlist.owner == me }
    var creating by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val ids = songs.map { it.id }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Add to playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                if (songs.size == 1) songs.first().title else songsLabel(songs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            SheetAction(Icons.Rounded.Add, "New playlist") { creating = true }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(editable, key = { it.id }) { item ->
                    PlaylistRow(
                        item = item,
                        isActive = false,
                        isPlaying = false,
                        onClick = {
                            container.scope.launch {
                                val r = container.library.addToPlaylist(item.id, ids)
                                snackbar.showSnackbar(
                                    if (r.isSuccess) "Added to ${item.name}" else "Couldn't update playlist: ${r.exceptionOrNull()?.message}",
                                )
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                container.scope.launch {
                    val r = container.library.createPlaylist(name, ids)
                    snackbar.showSnackbar(if (r.isSuccess) "Created $name" else "Couldn't create playlist")
                }
                onDismiss()
            },
        )
    }
}

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/**
 * Standard song actions wired to the player and library. Emits the add-to-playlist
 * sheet itself when needed, so callers just pass the returned [SongActions] down.
 */
@Composable
fun rememberSongActions(onRemove: ((SongEntity) -> Unit)? = null): SongActions {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    var addTarget by remember { mutableStateOf<SongEntity?>(null) }
    addTarget?.let { AddToPlaylistSheet(listOf(it)) { addTarget = null } }
    return remember(onRemove) {
        SongActions(
            onPlayNext = {
                container.player.playNext(it)
                container.scope.launch { snackbar.showSnackbar("Playing next: ${it.title}") }
            },
            onEnqueue = {
                container.player.enqueue(it)
                container.scope.launch { snackbar.showSnackbar("Added to queue") }
            },
            onAddToPlaylist = { addTarget = it },
            onToggleStar = { song ->
                container.scope.launch {
                    container.library.toggleStar(song).onFailure { snackbar.showSnackbar("Couldn't reach the server") }
                }
            },
            onRemove = onRemove,
        )
    }
}
