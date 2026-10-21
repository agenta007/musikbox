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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.PlaylistItem
import app.musikbox.desktop.data.SyncState
import kotlinx.coroutines.launch
import app.musikbox.data.DownloadRules
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.inDownloadList

@Composable
fun Sidebar(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val playlists by c.library.playlists.collectAsState()
    val saved by c.library.saved.collectAsState()
    val liked by c.library.liked.collectAsState()
    val settings by c.settings.state.collectAsState()
    val rules = DownloadRules(settings.autoDownloadAll, settings.autoDownloadSaved, settings.autoDownloadLiked)
    val onDisk = { p: PlaylistItem -> inDownloadList(p.id, p.offline, p.isFavorite, rules) }
    val player by c.player.state.collectAsState()
    val sync by c.library.syncState.collectAsState()
    val pending by c.downloads.pending.collectAsState()
    val peers by c.sync.peers.collectAsState()
    val syncedPeers = peers.count { it.state == app.musikbox.sync.PeerState.Synced }
    val menu = rememberPlaylistMenu()
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    val listState = rememberLazyListState()

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Text(
            buildAnnotatedString {
                append("musikbox")
                withStyle(SpanStyle(color = Palette.Accent)) { append(".") }
            },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 14.dp),
        )
        NavItem(Icons.Rounded.Home, "Home", nav.current == Screen.Home) { nav.go(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Search", nav.current is Screen.Search) { nav.go(Screen.Search()) }
        NavItem(Icons.Rounded.AutoAwesome, "Discover", nav.current == Screen.Discover) { nav.go(Screen.Discover) }
        NavItem(
            Icons.Rounded.Download, "Downloads", nav.current == Screen.Downloads,
            badge = if (pending.isNotEmpty()) "${pending.size}" else null,
        ) { nav.go(Screen.Downloads) }
        NavItem(
            Icons.Rounded.Devices, "Devices", nav.current == Screen.Devices,
            badge = syncedPeers.takeIf { it > 0 }?.toString(),
        ) { nav.go(Screen.Devices) }
        NavItem(Icons.Rounded.Settings, "Settings", nav.current == Screen.Settings) { nav.go(Screen.Settings) }

        Spacer(Modifier.height(14.dp))
        // Search by playlist name
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(38.dp)
                .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search playlists", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().tracksTextFocus(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(16.dp))
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                if (q.isEmpty()) liked?.let { l ->
                    item(key = "liked") { SidebarPlaylist(l, nav, player.sourcePlaylistId == l.id, player.isPlaying, menu, onDisk(l)) }
                }
                if (q.isEmpty() && saved.isNotEmpty()) {
                    item { SectionLabel("Saved") }
                    items(saved, key = { "f" + it.id }) { p -> SidebarPlaylist(p, nav, player.sourcePlaylistId == p.id, player.isPlaying, menu, onDisk(p)) }
                }
                item { SectionLabel(if (q.isEmpty()) "Playlists" else "${shown.size} matching") }
                items(shown, key = { "p" + it.id }) { p -> SidebarPlaylist(p, nav, player.sourcePlaylistId == p.id, player.isPlaying, menu, onDisk(p)) }
                if (q.isNotEmpty()) {
                    item {
                        Text(
                            "Search songs for “$q” →",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { nav.go(Screen.Search(q)) }.pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 22.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        SyncFooter(sync) { c.scope.launch { c.library.sync() } }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 1.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        badge?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SidebarPlaylist(
    p: PlaylistItem,
    nav: Navigator,
    active: Boolean,
    playing: Boolean,
    menu: (PlaylistItem) -> List<androidx.compose.foundation.ContextMenuItem>,
    downloaded: Boolean,
) {
    val selected = (nav.current as? Screen.Playlist)?.id == p.id
    ContextMenuArea(items = { menu(p) }) {
        Row(
            Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable { nav.go(Screen.Playlist(p.id)) }.pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.id == LIKED_PLAYLIST_ID) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(LikedGold.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Star, null, tint = LikedGold, modifier = Modifier.size(20.dp))
                }
            } else {
                CoverArt(p.playlist.coverArt, Modifier.size(36.dp), sizePx = 100, seed = p.id, iconSize = 14.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isFavorite) Icon(Icons.Rounded.Bookmark, "Saved", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp).padding(end = 2.dp))
                    if (downloaded) Icon(Icons.Rounded.DownloadForOffline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp).padding(end = 2.dp))
                    Text("${p.playlist.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (active) Equalizer(MaterialTheme.colorScheme.primary, playing, size = 12.dp)
        }
    }
}

@Composable
private fun SyncFooter(sync: SyncState, onSync: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (sync) {
            SyncState.Running -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Syncing playlists…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SyncState.Failed -> {
                Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("Offline — cached library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onSync, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Refresh, "Retry", modifier = Modifier.size(16.dp)) }
            }
            SyncState.Idle -> {
                Text("Library up to date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onSync, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Refresh, "Sync now", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
