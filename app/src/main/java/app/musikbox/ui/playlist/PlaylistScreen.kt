package app.musikbox.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.LocalSnackbar
import app.musikbox.ui.components.AddToPlaylistSheet
import app.musikbox.ui.components.ConfirmDialog
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.components.EmptyState
import app.musikbox.ui.components.MenuItem
import app.musikbox.ui.components.NameDialog
import app.musikbox.ui.components.SongRow
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.rememberArtworkColor
import app.musikbox.ui.components.rememberSongActions
import app.musikbox.ui.components.songsLabel
import app.musikbox.ui.components.tintFade
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Star

@Composable
fun PlaylistScreen(playlistId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val vm: PlaylistViewModel = viewModel { PlaylistViewModel(container, playlistId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val bottomInset = LocalBottomInset.current
    val listState = rememberLazyListState()

    val item = state.item
    val coverUrl = container.library.coverUrl(item?.playlist?.coverArt, 600)
    val background = MaterialTheme.colorScheme.background
    val tint by rememberArtworkColor(coverUrl, MaterialTheme.colorScheme.primary)
    val isSource = player.sourcePlaylistId == playlistId

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemoveDownloads by remember { mutableStateOf(false) }
    var addAll by remember { mutableStateOf(false) }

    val barAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f)
        }
    }

    // Leaving the screen is handled by the deleted playlist flow emitting null.
    if (state.loaded && item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.LibraryMusic, "Playlist not found", "It may have been deleted on the server.")
        }
        return
    }

    val baseActions = rememberSongActions()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
        ) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().background(tintFade(tint, background))) {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CoverArt(
                            item?.playlist?.coverArt,
                            Modifier
                                .size(236.dp)
                                .shadow(28.dp, RoundedCornerShape(14.dp), ambientColor = tint, spotColor = tint),
                            sizePx = 600,
                            shape = RoundedCornerShape(14.dp),
                            iconSize = 64.dp,
                            seed = playlistId,
                        )
                        Spacer(Modifier.height(22.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(
                                item?.name.orEmpty(),
                                style = MaterialTheme.typography.headlineMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item?.playlist?.comment?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    item?.playlist?.owner,
                                    item?.playlist?.songCount?.let(::songsLabel),
                                    item?.playlist?.durationSec?.takeIf { it > 0 }?.let(::formatLongDuration),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.offline && !state.downloadComplete && state.pendingCount == 0 && state.tracks.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Download waiting for network",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isLiked) {
                                Icon(
                                    Icons.Rounded.Star, "Liked songs", tint = Color(0xFFFFC145),
                                    modifier = Modifier.padding(12.dp).size(26.dp),
                                )
                            } else {
                                IconButton(onClick = { vm.toggleFavorite() }) {
                                    Icon(
                                        if (item?.isFavorite == true) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        if (item?.isFavorite == true) "Remove from saved" else "Save playlist",
                                        tint = if (item?.isFavorite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                            DownloadPill(state) {
                                when {
                                    state.autoReason != null -> scope.launch {
                                        snackbar.showSnackbar("${state.autoReason} \u2014 change it in Downloads")
                                    }
                                    state.offline -> confirmRemoveDownloads = true
                                    else -> vm.setOffline(true)
                                }
                            }
                            PlaylistMenu(
                                editable = state.editable,
                                onAddAll = { addAll = true },
                                onRename = { renaming = true },
                                onDelete = { confirmDelete = true },
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.play(shuffle = true) }, enabled = state.tracks.isNotEmpty()) {
                                Icon(
                                    Icons.Rounded.Shuffle, "Shuffle play",
                                    tint = if (isSource && player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .shadow(16.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable(enabled = state.tracks.isNotEmpty()) {
                                        if (isSource) container.player.togglePlayPause() else vm.play(shuffle = false)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isSource && player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (state.loaded && state.tracks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.LibraryMusic,
                        if ((item?.playlist?.songCount ?: 0) > 0) "Caching tracks…" else "This playlist is empty",
                        if ((item?.playlist?.songCount ?: 0) > 0) "Pull to refresh on the home screen if this takes long."
                        else "Find songs with search and add them here.",
                    )
                }
            }

            itemsIndexed(state.tracks, key = { _, t -> t.position }) { i, track ->
                val actions = if (state.isLiked) {
                    // In the liked list "remove" means un-star.
                    baseActions.copy(onRemove = { song -> baseActions.onToggleStar(song) }, removeLabel = "Remove from Liked songs")
                } else if (state.editable) {
                    baseActions.copy(onRemove = {
                        scope.launch {
                            vm.removeTrack(track.position).onFailure { snackbar.showSnackbar("Couldn't remove: ${it.message}") }
                        }
                    })
                } else baseActions
                SongRow(
                    song = track.song,
                    index = i + 1,
                    onClick = { vm.play(startIndex = i) },
                    actions = actions,
                    isCurrent = isSource && player.song?.id == track.song.id,
                    isPlaying = player.isPlaying,
                    downloaded = track.song.id in state.downloaded,
                )
            }
        }

        // Collapsing top bar
        Box(
            Modifier
                .fillMaxWidth()
                .background(lerpBar(tint, background, barAlpha)),
        ) {
            Row(
                Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(
                    item?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = barAlpha },
                )
            }
        }
    }

    if (renaming) {
        NameDialog("Rename playlist", "Save", initial = item?.name.orEmpty(), onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { vm.rename(name).onFailure { snackbar.showSnackbar("Couldn't rename: ${it.message}") } }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete playlist?",
            "\"${item?.name}\" will be deleted from the server for good.",
            "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            container.scope.launch {
                vm.delete().fold(
                    onSuccess = { snackbar.showSnackbar("Playlist deleted") },
                    onFailure = { snackbar.showSnackbar("Couldn't delete: ${it.message}") },
                )
            }
            onBack()
        }
    }
    if (confirmRemoveDownloads) {
        ConfirmDialog(
            "Remove downloads?",
            "Songs from this playlist will no longer be available offline (unless another offline playlist has them).",
            "Remove",
            onDismiss = { confirmRemoveDownloads = false },
        ) {
            confirmRemoveDownloads = false
            vm.setOffline(false)
        }
    }
    if (addAll) {
        AddToPlaylistSheet(state.tracks.map { it.song }) { addAll = false }
    }
}

private fun lerpBar(tint: Color, bg: Color, alpha: Float): Color =
    androidx.compose.ui.graphics.lerp(bg, tint, 0.18f).copy(alpha = alpha)

/** Labelled download toggle: "Download" → live "3/8" progress → "Downloaded". */
@Composable
private fun DownloadPill(state: PlaylistUiState, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val total = state.tracks.size
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .height(36.dp)
            .clip(shape)
            .background(if (state.downloadComplete) primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (state.offline) primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = total > 0, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.downloadComplete -> {
                Icon(Icons.Rounded.DownloadDone, null, tint = primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Downloaded", style = MaterialTheme.typography.labelLarge, color = primary)
            }
            state.offline -> {
                CircularProgressIndicator(
                    progress = { if (total == 0) 0f else state.downloadedCount / total.toFloat() },
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
                Spacer(Modifier.width(8.dp))
                Text("${state.downloadedCount} / $total", style = MaterialTheme.typography.labelLarge, color = primary)
            }
            else -> {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PlaylistMenu(editable: Boolean, onAddAll: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
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
            MenuItem("Add all to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; onAddAll() }
            if (editable) {
                MenuItem("Rename", Icons.Rounded.Edit) { open = false; onRename() }
                MenuItem("Delete playlist", Icons.Rounded.DeleteOutline) { open = false; onDelete() }
            }
        }
    }
}
