package app.musikbox.desktop.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.PlaylistItem
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.songsLabel
import java.util.Calendar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.DownloadDone

private enum class Filter(val label: String) { All("All"), Mine("Mine"), Offline("Offline") }
private enum class Sort(val label: String) { Name("Name"), Updated("Recently updated"), Size("Most songs") }

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val all by c.library.playlists.collectAsState()
    val recent by c.library.recent.collectAsState()
    val saved by c.library.saved.collectAsState()
    val liked by c.library.liked.collectAsState()
    val player by c.player.state.collectAsState()
    val settings by c.settings.state.collectAsState()
    val menu = rememberPlaylistMenu()

    var filter by remember { mutableStateOf(Filter.All) }
    var sort by remember { mutableStateOf(Sort.Name) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = all
        .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        .filter {
            when (filter) {
                Filter.All -> true
                Filter.Mine -> it.playlist.owner == null || it.playlist.owner == settings.username
                Filter.Offline -> c.library.inList(it.id)
            }
        }
        .let { list ->
            when (sort) {
                Sort.Name -> list
                Sort.Updated -> list.sortedByDescending { it.playlist.changed ?: "" }
                Sort.Size -> list.sortedByDescending { it.playlist.songCount }
            }
        }
    val gridState = rememberLazyGridState()
    val play: (PlaylistItem) -> Unit = { p ->
        if (player.sourcePlaylistId == p.id) c.player.togglePlay() else c.player.playPlaylist(p.id)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(320.dp)
                .background(Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.14f), Color.Transparent))),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(176.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full { Greeting() }

            if (all.isEmpty()) {
                full {
                    Column(Modifier.padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No playlists yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Create playlists in Navidrome, or search for songs and add them to a new playlist.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            liked?.takeIf { q.isEmpty() }?.let { l ->
                full {
                    LikedBanner(
                        liked = l,
                        downloaded = settings.let { c.library.inList(l.id) }, // reads settings → recomposes on rule changes
                        active = player.sourcePlaylistId == l.id,
                        playing = player.isPlaying,
                        onOpen = { nav.go(Screen.Playlist(l.id)) },
                        onPlay = { play(l) },
                        menu = menu,
                    )
                }
            }

            if (recent.isNotEmpty() && q.isEmpty()) {
                full { SectionTitle("Jump back in") }
                full {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        items(recent, key = { it.id }) { p ->
                            PlaylistCard(p, player.sourcePlaylistId == p.id, player.isPlaying, { nav.go(Screen.Playlist(p.id)) }, { play(p) }, menu, Modifier.width(176.dp))
                        }
                    }
                }
            }

            if (saved.isNotEmpty() && q.isEmpty()) {
                full { SectionTitle("Saved playlists") }
                full {
                    BoxWithConstraints {
                        val columns = (maxWidth / 300.dp).toInt().coerceIn(1, 4)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            saved.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { p ->
                                        FavoriteTile(p, player.sourcePlaylistId == p.id, player.isPlaying, { nav.go(Screen.Playlist(p.id)) }, { play(p) }, menu, Modifier.weight(1f))
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }

            if (all.isNotEmpty()) {
                full {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Playlists", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                            SearchField(query, { query = it }, "Search playlists", Modifier.width(280.dp))
                            Spacer(Modifier.width(8.dp))
                            SortMenu(sort) { sort = it }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Filter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f, onClick = { filter = f }, label = { Text(f.label) },
                                    shape = RoundedCornerShape(50), border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    full {
                        Column {
                            Text(
                                if (q.isNotEmpty()) "No playlists match “$q”" else "Nothing here yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (q.isNotEmpty()) {
                                TextButton(onClick = { nav.go(Screen.Search(q)) }) { Text("Search songs on the server for “$q”") }
                            }
                        }
                    }
                }
                items(shown, key = { it.id }) { p ->
                    PlaylistCard(p, player.sourcePlaylistId == p.id, player.isPlaying, { nav.go(Screen.Playlist(p.id)) }, { play(p) }, menu)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Greeting() {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    Text(greeting, style = MaterialTheme.typography.displaySmall)
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth().tracksTextFocus(),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(16.dp)) }
    }
}

@Composable
private fun SortMenu(current: Sort, onSelect: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; onSelect(s) }) }
        }
    }
}

/** Grid card: hover reveals a play button; right-click for the playlist menu. */
@Composable
fun PlaylistCard(
    p: PlaylistItem,
    active: Boolean,
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: (PlaylistItem) -> List<ContextMenuItem>,
    modifier: Modifier = Modifier,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    ContextMenuArea(items = { menu(p) }) {
        Column(
            modifier.clip(RoundedCornerShape(12.dp))
                .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .hoverable(hover).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CoverArt(p.playlist.coverArt, Modifier.fillMaxSize().shadow(10.dp, RoundedCornerShape(10.dp)), sizePx = 400, shape = RoundedCornerShape(10.dp), iconSize = 40.dp, seed = p.id)
                androidx.compose.animation.AnimatedVisibility(hovered || active, Modifier.align(Alignment.BottomEnd).padding(10.dp), enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                    Box(
                        Modifier.size(46.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            active && playing && !hovered -> Equalizer(MaterialTheme.colorScheme.onPrimary, true, size = 16.dp)
                            active && playing -> Icon(Icons.Rounded.Pause, "Pause", tint = MaterialTheme.colorScheme.onPrimary)
                            else -> Icon(Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append(songsLabel(p.playlist.songCount))
                    if (p.playlist.durationSec > 0) append(" · ").append(formatLongDuration(p.playlist.durationSec))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

@Composable
private fun FavoriteTile(
    p: PlaylistItem,
    active: Boolean,
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: (PlaylistItem) -> List<ContextMenuItem>,
    modifier: Modifier = Modifier,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // [modifier] (e.g. weight) must be on the direct child of the caller's Row, not inside ContextMenuArea.
    Box(modifier) { ContextMenuArea(items = { menu(p) }) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp))
                .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                .hoverable(hover).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(p.playlist.coverArt, Modifier.size(64.dp), sizePx = 160, shape = RoundedCornerShape(0.dp), seed = p.id)
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            when {
                hovered -> Box(
                    Modifier.padding(end = 10.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (active && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
                active -> Equalizer(MaterialTheme.colorScheme.primary, playing, Modifier.padding(end = 14.dp))
            }
        }
    } }
}

/** The ⭐ liked-songs list, set apart from playlists at the top of home. */
@Composable
private fun LikedBanner(
    liked: PlaylistItem,
    downloaded: Boolean,
    active: Boolean,
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: (PlaylistItem) -> List<ContextMenuItem>,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    ContextMenuArea(items = { menu(liked) }) {
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(LikedGold.copy(alpha = if (hovered) 0.42f else 0.32f), MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainerHigh),
                    ),
                )
                .hoverable(hover).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(LikedGold.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Star, null, tint = LikedGold, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(liked.name, style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (downloaded) {
                        Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        buildString {
                            append(songsLabel(liked.playlist.songCount))
                            if (liked.playlist.durationSec > 0) append(" · ").append(formatLongDuration(liked.playlist.durationSec))
                            if (liked.playlist.songCount == 0) append(" · star songs with \u2606 to collect them here")
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (liked.playlist.songCount > 0) {
                Box(
                    Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay).pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) Equalizer(MaterialTheme.colorScheme.onPrimary, playing)
                    else Icon(Icons.Rounded.PlayArrow, "Play liked songs", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}
