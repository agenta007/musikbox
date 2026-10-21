package app.musikbox.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.songsLabel
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import app.musikbox.data.DownloadRules
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.autoReason
import app.musikbox.data.inDownloadList

@Composable
fun PlaylistScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val item by remember(id) { c.library.playlist(id) }.collectAsState(c.library.playlists.value.firstOrNull { it.id == id })
    val tracks by remember(id) { c.library.tracks(id) }.collectAsState(c.library.tracksNow(id))
    val player by c.player.state.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val pending by c.downloads.pending.collectAsState()
    val settings by c.settings.state.collectAsState()
    val listState = rememberLazyListState()

    val p = item
    if (p == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Text("Playlist not found", style = MaterialTheme.typography.titleLarge)
        }
        return
    }

    val isLiked = id == LIKED_PLAYLIST_ID
    val editable = !isLiked && (p.playlist.owner == null || p.playlist.owner == settings.username)
    val rules = DownloadRules(settings.autoDownloadAll, settings.autoDownloadSaved, settings.autoDownloadLiked)
    val offline = inDownloadList(id, p.offline, p.isFavorite, rules)
    val auto = autoReason(id, p.isFavorite, rules)
    val doneCount = tracks.count { it.id in downloaded }
    val pendingCount = tracks.count { it.id in pending }
    val isSource = player.sourcePlaylistId == id
    val tint by rememberArtworkColor(c.library.coverUrl(p.playlist.coverArt, 600), MaterialTheme.colorScheme.primary)
    val bg = MaterialTheme.colorScheme.background

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemoveDl by remember { mutableStateOf(false) }
    var addAll by remember { mutableStateOf(false) }

    val songMenu = rememberSongMenu()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().background(tintFade(tint, bg, 0.45f))) {
                    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 20.dp)) {
                        if (nav.canGoBack) {
                            IconButton(onClick = nav::back, modifier = Modifier.padding(bottom = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            CoverArt(
                                p.playlist.coverArt,
                                Modifier.size(220.dp).shadow(30.dp, RoundedCornerShape(12.dp), ambientColor = tint, spotColor = tint),
                                sizePx = 600, shape = RoundedCornerShape(12.dp), iconSize = 64.dp, seed = id,
                            )
                            Spacer(Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text("PLAYLIST", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                                Text(p.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                p.playlist.comment?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    listOfNotNull(
                                        p.playlist.owner,
                                        songsLabel(p.playlist.songCount),
                                        p.playlist.durationSec.takeIf { it > 0 }?.let(::formatLongDuration),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(54.dp).shadow(14.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable(enabled = tracks.isNotEmpty()) { if (isSource) c.player.togglePlay() else c.player.playSongs(tracks, 0, id, shuffle = false) }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isSource && player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            IconButton(onClick = { c.player.playSongs(tracks, 0, id, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Rounded.Shuffle, "Shuffle play", tint = if (isSource && player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                            if (!isLiked) {
                                IconButton(onClick = { c.library.toggleFavorite(id) }) {
                                    Icon(
                                        if (p.isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        if (p.isFavorite) "Remove from saved" else "Save playlist",
                                        tint = if (p.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DownloadPill(offline, doneCount, tracks.size, pendingCount) {
                                when {
                                    auto != null -> c.scope.launch { snackbar.showSnackbar("$auto — change it in Downloads") }
                                    offline -> confirmRemoveDl = true
                                    else -> c.library.setOffline(id, true)
                                }
                            }
                            PlaylistMoreButton(
                                buildList {
                                    add(ContextMenuItem("Add all to playlist…") { addAll = true })
                                    if (editable) {
                                        add(ContextMenuItem("Rename…") { renaming = true })
                                        add(ContextMenuItem("Delete playlist") { confirmDelete = true })
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item(key = "table-header") { TrackHeader(Modifier.padding(horizontal = 20.dp)) }
            if (tracks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (p.playlist.songCount > 0) "Caching tracks…" else "This playlist is empty — find songs with Search and add them here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp),
                    )
                }
            }
            itemsIndexed(tracks, key = { i, s -> "$i-${s.id}" }) { i, song ->
                TrackRow(
                    song = song,
                    index = i + 1,
                    isCurrent = isSource && player.song?.id == song.id,
                    isPlaying = player.isPlaying,
                    downloaded = song.id in downloaded,
                    onPlay = { c.player.playSongs(tracks, i, id) },
                    menuItems = {
                        songMenu.items(song) + if (editable) listOf(
                            ContextMenuItem("Remove from this playlist") {
                                c.scope.launch { c.library.removeFromPlaylist(id, i).onFailure { snackbar.showSnackbar("Couldn't remove: ${it.message}") } }
                            },
                        ) else emptyList()
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    if (renaming) {
        NameDialog("Rename playlist", "Save", p.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            c.scope.launch { c.library.renamePlaylist(id, name).onFailure { snackbar.showSnackbar("Couldn't rename: ${it.message}") } }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete playlist?", "“${p.name}” will be deleted from the server for good.", "Delete", { confirmDelete = false }) {
            confirmDelete = false
            nav.back()
            c.scope.launch {
                c.library.deletePlaylist(id).fold({ snackbar.showSnackbar("Playlist deleted") }, { snackbar.showSnackbar("Couldn't delete: ${it.message}") })
            }
        }
    }
    if (confirmRemoveDl) {
        ConfirmDialog("Remove download?", "Songs from this playlist will no longer be available offline (unless another downloaded playlist has them).", "Remove", { confirmRemoveDl = false }) {
            confirmRemoveDl = false
            c.library.setOffline(id, false)
        }
    }
    if (addAll) AddToPlaylistDialog(tracks) { addAll = false }
}

/** "Download" → live "3 / 8" → "Downloaded", like the Android app. */
@Composable
fun DownloadPill(offline: Boolean, done: Int, total: Int, pending: Int, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val complete = offline && total > 0 && done >= total
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.padding(horizontal = 6.dp).height(36.dp).clip(shape)
            .background(if (complete) primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (offline) primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = total > 0, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            complete -> {
                Icon(Icons.Rounded.DownloadDone, null, tint = primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Downloaded", style = MaterialTheme.typography.labelLarge, color = primary)
            }
            offline -> {
                CircularProgressIndicator(
                    progress = { if (total == 0) 0f else done / total.toFloat() },
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = 0.15f),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (pending > 0) "$done / $total" else "$done / $total · waiting", style = MaterialTheme.typography.labelLarge, color = primary)
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
private fun PlaylistMoreButton(items: List<ContextMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        MenuDropdown(open, { open = false }, items)
    }
}

