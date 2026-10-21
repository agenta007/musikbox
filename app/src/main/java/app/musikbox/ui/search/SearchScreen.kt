package app.musikbox.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.musikbox.AppContainer
import app.musikbox.data.SearchResults
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.components.EmptyState
import app.musikbox.ui.components.PlaylistRow
import app.musikbox.ui.components.SongRow
import app.musikbox.ui.components.rememberPlaylistActions
import app.musikbox.ui.components.rememberSongActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

data class SearchUi(val query: String = "", val loading: Boolean = false, val results: SearchResults? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    val ui: StateFlow<SearchUi> = query
        .map { it.trim() }
        .debounce(300)
        .distinctUntilChanged()
        .transformLatest { q ->
            if (q.isEmpty()) {
                emit(SearchUi())
            } else {
                emit(SearchUi(q, loading = true))
                emit(SearchUi(q, loading = false, results = container.library.search(q)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())
}

@Composable
fun SearchScreen(initialQuery: String = "", onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val downloaded by container.offline.downloaded.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val actions = rememberSongActions()
    val showActions = rememberPlaylistActions(onOpenPlaylist)
    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank() && vm.query.value.isEmpty()) vm.query.value = initialQuery
        else focus.requestFocus()
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 68.dp, bottom = LocalBottomInset.current + 24.dp),
        ) {
            val results = ui.results
            if (ui.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(2.dp))) }
            }
            if (results != null && !results.fromServer) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Offline — searching songs in your cached playlists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (results != null && results.playlists.isEmpty() && results.songs.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing matches “${ui.query}”.")
                }
            }
            if (results != null && results.playlists.isNotEmpty()) {
                item { Header("Playlists") }
                items(results.playlists, key = { "p" + it.id }) { p ->
                    PlaylistRow(p, { onOpenPlaylist(p.id) }, p.id == player.sourcePlaylistId, player.isPlaying, onMore = { showActions(p) })
                }
            }
            if (results != null && results.songs.isNotEmpty()) {
                item { Header("Songs") }
                itemsIndexed(results.songs, key = { i, s -> "s$i-" + s.id }) { i, song ->
                    SongRow(
                        song = song,
                        onClick = {
                            focusManager.clearFocus()
                            container.player.playSongs(results.songs, i)
                        },
                        actions = actions,
                        isCurrent = player.song?.id == song.id,
                        isPlaying = player.isPlaying,
                        downloaded = song.id in downloaded,
                    )
                }
            }
            if (results == null && !ui.loading && query.isBlank()) {
                item {
                    EmptyState(
                        Icons.Rounded.Search,
                        "Find something to add",
                        "Search your playlists, or the whole server for songs to play or add to a playlist.",
                    )
                }
            }
        }

        // Search field
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Songs, artists, playlists", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
}
