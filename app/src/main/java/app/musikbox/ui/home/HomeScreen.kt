package app.musikbox.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.downloads.DownloadsSummary
import app.musikbox.ui.downloads.PlaylistDownloadRow
import app.musikbox.playback.DownloadStatus
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import app.musikbox.ui.components.formatBytes
import app.musikbox.ui.components.rememberPlaylistActions
import app.musikbox.ui.components.songsLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.musikbox.data.SyncState
import app.musikbox.data.local.PlaylistItem
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.components.EmptyState
import app.musikbox.ui.components.FavoriteTile
import app.musikbox.ui.components.MenuItem
import app.musikbox.ui.components.PlaylistRow
import app.musikbox.ui.components.RecentPlaylistCard
import app.musikbox.ui.theme.Palette
import java.util.Calendar
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import app.musikbox.sync.PeerState
import app.musikbox.sync.toEntity
import app.musikbox.ui.LocalSnackbar
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.shape.CircleShape
import app.musikbox.data.LIKED_PLAYLIST_NAME
import app.musikbox.data.inDownloadList
import app.musikbox.data.settings.downloadRules
import app.musikbox.ui.components.Equalizer
import app.musikbox.ui.components.formatLongDuration

@Composable
fun HomeScreen(
    onOpenPlaylist: (String) -> Unit,
    /** Opens server-wide search, optionally pre-filled. */
    onOpenSearch: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDiscover: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val downloadStatus by container.offline.status.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val bottomInset = LocalBottomInset.current
    val showActions = rememberPlaylistActions(onOpenPlaylist)
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = state.searching) {
        vm.setQuery("")
        focusManager.clearFocus()
    }

    val activeId = player.sourcePlaylistId
    fun isActive(p: PlaylistItem) = p.id == activeId

    PullToRefreshBox(
        isRefreshing = state.sync == SyncState.Running && state.totalPlaylists > 0,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
        ) {
            item(key = "header") {
                Header({ onOpenSearch("") }, onOpenSettings, onOpenDiscover)
            }

            item(key = "handoff") { HandoffBanner() }

            if (state.totalPlaylists > 0) {
                item(key = "search") {
                    PlaylistSearchField(state.query, vm::setQuery, onDone = { focusManager.clearFocus() })
                }
            }

            item(key = "sync") {
                SyncBanner(state.sync, firstSync = state.totalPlaylists == 0, onRetry = vm::refresh)
            }

            if (state.loaded && state.totalPlaylists == 0 && state.sync !is SyncState.Running) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.LibraryMusic,
                        "No playlists yet",
                        "Create playlists in Navidrome, or add songs to a new playlist from search.",
                    )
                }
            }

            if (state.searching) {
                item(key = "search-count") {
                    Text(
                        when (state.playlists.size) {
                            0 -> "No playlists match \u201c${state.query.trim()}\u201d"
                            1 -> "1 playlist"
                            else -> "${state.playlists.size} playlists"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(state.playlists, key = { "q-" + it.id }) { p ->
                    PlaylistRow(p, { onOpenPlaylist(p.id) }, isActive(p), player.isPlaying, Modifier.animateItem(), onMore = { showActions(p) })
                }
                item(key = "search-server") {
                    TextButton(
                        onClick = { onOpenSearch(state.query.trim()) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Search songs on the server for \u201c${state.query.trim()}\u201d")
                    }
                }
                return@LazyColumn
            }

            state.liked?.let { liked ->
                item(key = "liked") {
                    LikedCard(
                        liked = liked,
                        isActive = isActive(liked),
                        isPlaying = player.isPlaying,
                        onOpen = { onOpenPlaylist(liked.id) },
                        onPlay = {
                            if (isActive(liked)) container.player.togglePlayPause() else container.player.playPlaylist(liked.id)
                        },
                        onMore = { showActions(liked) },
                    )
                }
            }

            if (state.recent.isNotEmpty()) {
                item(key = "recent-title") { SectionTitle("Jump back in") }
                item(key = "recent") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.recent, key = { it.id }) { p ->
                            RecentPlaylistCard(
                                item = p,
                                onOpen = { onOpenPlaylist(p.id) },
                                onPlay = {
                                    if (isActive(p)) container.player.togglePlayPause() else container.player.playPlaylist(p.id)
                                },
                                isActive = isActive(p),
                                isPlaying = player.isPlaying,
                                onLongClick = { showActions(p) },
                            )
                        }
                    }
                }
            }

            if (state.saved.isNotEmpty()) {
                item(key = "fav-title") { SectionTitle("Saved playlists") }
                state.saved.chunked(2).forEachIndexed { i, pair ->
                    item(key = "fav-$i") {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { p ->
                                FavoriteTile(
                                    p, { onOpenPlaylist(p.id) }, isActive(p), player.isPlaying,
                                    Modifier.weight(1f),
                                    onLongClick = { showActions(p) },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (state.totalPlaylists > 0) {
                item(key = "discover-card") { DiscoverCard(onOpenDiscover) }
            }

            if (state.totalPlaylists > 0) {
                downloadsSection(downloads, downloadStatus, onOpenPlaylist, onOpenDownloads, showActions)
            }

            if (state.totalPlaylists > 0) {
                item(key = "all-title") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 28.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Playlists", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        SortMenu(state.sort, vm::setSort)
                    }
                }
                item(key = "filters") {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlaylistFilter.entries.forEach { f ->
                            FilterChip(
                                selected = state.filter == f,
                                onClick = { vm.setFilter(f) },
                                label = { Text(f.label) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                border = null,
                            )
                        }
                    }
                }
                if (state.playlists.isEmpty()) {
                    item(key = "filtered-empty") {
                        Text(
                            if (state.filter == PlaylistFilter.Offline) "Nothing downloaded yet. Tap \u22ee on a playlist and choose Download."
                            else "No playlists match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                items(state.playlists, key = { "pl-" + it.id }) { p ->
                    PlaylistRow(p, { onOpenPlaylist(p.id) }, isActive(p), player.isPlaying, Modifier.animateItem(), onMore = { showActions(p) })
                }
            }
        }
    }
}

@Composable
private fun Header(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenDiscover: () -> Unit) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.16f), Color.Transparent))),
    ) {
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("musikbox")
                        withStyle(SpanStyle(color = Palette.Accent)) { append(".") }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(greeting, style = MaterialTheme.typography.headlineLarge)
            }
            IconButton(onClick = onOpenDiscover) { Icon(Icons.Rounded.AutoAwesome, "Discover") }
            IconButton(onClick = onOpenSearch) { Icon(Icons.Rounded.Search, "Search") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun SyncBanner(sync: SyncState, firstSync: Boolean, onRetry: () -> Unit) {
    AnimatedVisibility(sync is SyncState.Failed || (sync is SyncState.Running && firstSync)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            if (sync is SyncState.Running) {
                Text("Caching your playlists…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
            } else if (sync is SyncState.Failed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        "Offline — showing cached playlists",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(current: PlaylistSort, onSelect: (PlaylistSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(current.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            PlaylistSort.entries.forEach { s ->
                MenuItem(s.label, Icons.AutoMirrored.Rounded.Sort) { open = false; onSelect(s) }
            }
        }
    }
}


@Composable
private fun PlaylistSearchField(query: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search playlists", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
        } else {
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun LazyListScope.downloadsSection(
    downloads: DownloadsSummary,
    status: DownloadStatus,
    onOpenPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    showActions: (PlaylistItem) -> Unit,
) {
    item(key = "dl-title") {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                val line = when {
                    downloads.totalSongs == 0 -> null
                    status.paused -> "Paused \u00b7 ${downloads.downloadedSongs} of ${songsLabel(downloads.totalSongs)}"
                    downloads.pendingSongs > 0 && status.waitingFor != null -> "Waiting for ${status.waitingFor}"
                    downloads.pendingSongs > 0 -> "Downloading ${songsLabel(downloads.pendingSongs)}\u2026"
                    downloads.failedSongs > 0 -> "${songsLabel(downloads.failedSongs)} failed"
                    else -> formatBytes(downloads.bytes)
                }
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            downloads.failedSongs > 0 && downloads.pendingSongs == 0 -> MaterialTheme.colorScheme.error
                            downloads.pendingSongs > 0 && !status.paused -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            TextButton(onClick = onOpenDownloads) {
                Text("Manage")
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
    }
    when {
        downloads.autoAll -> item(key = "dl-all") {
            DownloadCard(
                icon = Icons.Rounded.DownloadDone,
                title = "All playlists",
                subtitle = "${downloads.downloadedSongs} of ${songsLabel(downloads.totalSongs)} \u00b7 ${formatBytes(downloads.bytes)}",
                fraction = downloads.fraction.takeIf { downloads.totalSongs > 0 } ?: 1f,
                onClick = onOpenDownloads,
            )
        }
        downloads.playlists.isEmpty() -> item(key = "dl-hint") {
            DownloadCard(
                icon = Icons.Rounded.Download,
                title = "Listen offline",
                subtitle = "Tap \u22ee on any playlist and choose Download, or add playlists from Manage.",
                fraction = null,
                onClick = onOpenDownloads,
            )
        }
        else -> items(downloads.playlists, key = { "dl-" + it.item.id }) { d ->
            PlaylistDownloadRow(d, status, onClick = { onOpenPlaylist(d.item.id) }, onMore = { showActions(d.item) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun DownloadCard(icon: ImageVector, title: String, subtitle: String, fraction: Float?, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fraction != null && fraction < 1f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** "Playing on <other device>" with a one-tap handoff, when another of your devices is playing. */
@Composable
private fun HandoffBanner() {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val peers by container.sync.peers.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val peer = peers.firstOrNull { it.nowPlaying?.playing == true && it.state == PeerState.Synced } ?: return
    val song = peer.nowPlaying?.current ?: return
    if (player.isPlaying) return
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Playing on ${peer.displayName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(song.title + (song.artist?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = {
            container.scope.launch {
                val np = runCatching { container.sync.takeOver(peer) }.getOrNull()
                if (np != null && np.queue.isNotEmpty()) {
                    container.player.resumeQueue(np.queue.map { it.toEntity() }, np.index, np.positionMs, np.playlistId)
                } else snackbar.showSnackbar("Couldn't reach ${peer.displayName}")
            }
        }) { Text("Continue here") }
    }
}

@Composable
private fun DiscoverCard(onOpen: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), MaterialTheme.colorScheme.surfaceContainerHigh)))
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Discover Mix", style = MaterialTheme.typography.titleMedium)
            Text(
                "Songs from your library you haven't heard yet, picked by your taste",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

/** The ⭐ liked-songs list: always first, visually distinct from playlists. */
@Composable
private fun LikedCard(
    liked: PlaylistItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val downloaded = inDownloadList(liked.id, liked.offline, false, settings.downloadRules)
    val gold = Color(0xFFFFC145)
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(gold.copy(alpha = 0.38f), MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), MaterialTheme.colorScheme.surfaceContainerHigh)))
            .combinedClickable(onClick = onOpen, onLongClick = onMore)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(gold.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Star, null, tint = gold, modifier = Modifier.size(32.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(LIKED_PLAYLIST_NAME, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    buildString {
                        append(songsLabel(liked.playlist.songCount))
                        if (liked.playlist.durationSec > 0) append(" · ").append(formatLongDuration(liked.playlist.durationSec))
                        if (liked.playlist.songCount == 0) append(" · tap \u2606 on any song")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (liked.playlist.songCount > 0) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (isActive) Equalizer(MaterialTheme.colorScheme.onPrimary, isPlaying, size = 16.dp)
                else Icon(Icons.Rounded.PlayArrow, "Play liked songs", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
