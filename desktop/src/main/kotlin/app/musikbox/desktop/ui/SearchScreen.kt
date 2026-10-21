package app.musikbox.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.data.SearchResults
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(initialQuery: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val player by c.player.state.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val playlistMenu = rememberPlaylistMenu()
    val songMenu = rememberSongMenu()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; loading = false; return@LaunchedEffect }
        delay(300) // debounce
        loading = true
        results = c.library.search(q)
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
            item {
                SearchField(query, { query = it }, "Songs, artists, albums, playlists", Modifier.fillMaxWidth().focusRequester(focus))
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
            val r = results
            if (r != null && !r.fromServer) {
                item {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Offline — searching songs in your cached playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (r == null && !loading) {
                item {
                    Text(
                        "Search your playlists by name, or the whole server for songs to play or add to a playlist.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }
            if (r != null && r.playlists.isEmpty() && r.songs.isEmpty()) {
                item { Text("No results for “${query.trim()}”", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp)) }
            }
            if (r != null && r.playlists.isNotEmpty()) {
                item { SectionTitle("Playlists") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) {
                        items(r.playlists, key = { it.id }) { p ->
                            PlaylistCard(
                                p, player.sourcePlaylistId == p.id, player.isPlaying,
                                { nav.go(Screen.Playlist(p.id)) }, { c.player.playPlaylist(p.id) }, playlistMenu, Modifier.width(170.dp),
                            )
                        }
                    }
                }
            }
            if (r != null && r.songs.isNotEmpty()) {
                item { SectionTitle("Songs") }
                item { TrackHeader(Modifier.padding(top = 8.dp)) }
                itemsIndexed(r.songs, key = { i, s -> "$i-${s.id}" }) { i, song ->
                    TrackRow(
                        song, index = i + 1,
                        isCurrent = player.song?.id == song.id, isPlaying = player.isPlaying,
                        downloaded = song.id in downloaded,
                        onPlay = { c.player.playSongs(r.songs, i) },
                        menuItems = { songMenu.items(song) },
                    )
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
