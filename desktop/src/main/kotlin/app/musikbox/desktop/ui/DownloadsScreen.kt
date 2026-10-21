package app.musikbox.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.PlaylistItem
import app.musikbox.ui.components.formatBytes
import app.musikbox.ui.components.songsLabel
import app.musikbox.data.DownloadRules
import app.musikbox.data.autoReason

private data class Entry(val item: PlaylistItem, val done: Int, val total: Int, val pending: Int, val bytes: Long) {
    val complete get() = total > 0 && done >= total
}

@Composable
fun DownloadsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val playlists by c.library.playlists.collectAsState()
    val tracks by c.library.allTracks.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val pending by c.downloads.pending.collectAsState()
    val settings by c.settings.state.collectAsState()
    val menu = rememberPlaylistMenu()
    val listState = rememberLazyListState()

    val liked by c.library.liked.collectAsState()
    // Reading settings keeps this in step with rule switches.
    val offlineLists = settings.let { (listOfNotNull(liked) + playlists).filter { c.library.inList(it.id) } }
    val entries = offlineLists.map { p ->
        val t = tracks[p.id].orEmpty()
        val done = t.filter { it.id in downloaded }
        Entry(p, done.size, t.size, t.count { it.id in pending }, done.sumOf { it.sizeBytes ?: 0L })
    }.sortedWith(compareBy({ it.complete }, { it.item.name.lowercase() }))
    // Songs shared between playlists count once.
    val songs = offlineLists.flatMap { tracks[it.id].orEmpty() }.associateBy { it.id }
    val totalBytes = songs.values.filter { it.id in downloaded }.sumOf { it.sizeBytes ?: 0L }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("Downloads", style = MaterialTheme.typography.displaySmall)
                Text(
                    when {
                        pending.isNotEmpty() -> "Downloading ${songsLabel(pending.size)}…"
                        songs.isEmpty() -> "Download playlists to listen without a connection."
                        else -> "${songs.keys.count { it in downloaded }} of ${songsLabel(songs.size)} on this computer · ${formatBytes(totalBytes)}"
                    },
                    color = if (pending.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                )
            }
            item {
                DownloadRulesPanel()
                Spacer(Modifier.height(18.dp))
            }
            if (entries.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 20.dp)) {
                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Right-click any playlist and choose Download, or use the Download button inside a playlist.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(entries, key = { it.item.id }) { e ->
                ContextMenuArea(items = { menu(e.item) }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.go(Screen.Playlist(e.item.id)) }
                            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(e.item.playlist.coverArt, Modifier.size(56.dp), sizePx = 160, seed = e.item.id)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    e.complete -> "${songsLabel(e.total)} · ${formatBytes(e.bytes)}"
                                    e.pending > 0 -> "Downloading · ${e.done} of ${e.total}"
                                    else -> "Waiting for network · ${e.done} of ${e.total}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                            if (!e.complete) {
                                LinearProgressIndicator(
                                    progress = { if (e.total == 0) 0f else e.done / e.total.toFloat() },
                                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                                    drawStopIndicator = {},
                                )
                            }
                        }
                        if (e.complete) Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
                        if (autoReason(e.item.id, e.item.isFavorite, DownloadRules(settings.autoDownloadAll, settings.autoDownloadSaved, settings.autoDownloadLiked)) == null) {
                            IconButton(onClick = { c.library.setOffline(e.item.id, false) }) {
                                Icon(Icons.Rounded.FileDownloadOff, "Remove download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
