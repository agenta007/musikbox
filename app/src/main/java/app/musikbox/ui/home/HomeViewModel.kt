package app.musikbox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musikbox.AppContainer
import app.musikbox.data.PlaylistSort
import app.musikbox.data.PlaylistSortFields
import app.musikbox.data.SyncState
import app.musikbox.data.inDownloadList
import app.musikbox.data.sortedForPlaylists
import app.musikbox.data.local.PlaylistItem
import app.musikbox.data.settings.downloadRules
import app.musikbox.ui.downloads.DownloadsSummary
import app.musikbox.ui.downloads.summarizeDownloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PlaylistFilter(val label: String) { All("All"), Mine("Mine"), Offline("Offline") }

data class HomeUiState(
    val loaded: Boolean = false,
    val recent: List<PlaylistItem> = emptyList(),
    /** ⭐ starred songs as a list; null before the first sync. */
    val liked: PlaylistItem? = null,
    /** Bookmarked playlists. */
    val saved: List<PlaylistItem> = emptyList(),
    val playlists: List<PlaylistItem> = emptyList(),
    val totalPlaylists: Int = 0,
    val filter: PlaylistFilter = PlaylistFilter.All,
    val sort: PlaylistSort = PlaylistSort.Name,
    val sortReversed: Boolean = false,
    val query: String = "",
    val sync: SyncState = SyncState.Idle,
) {
    val searching get() = query.isNotBlank()
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library
    private val filter = MutableStateFlow(PlaylistFilter.All)
    private val query = MutableStateFlow("")

    val state: StateFlow<HomeUiState> = combine(
        combine(library.recent, library.liked) { r, l -> r to l },
        library.saved,
        library.playlists,
        combine(filter, query) { f, q -> f to q },
        combine(library.syncState, container.settings.settings) { sy, st -> sy to st },
    ) { (recent, liked), saved, all, (f, q), (sync, settings) ->
        val rules = settings.downloadRules
        val me = container.settings.current.credentials?.username
        val needle = q.trim()
        val filtered = all
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .filter {
                when (f) {
                    PlaylistFilter.All -> true
                    PlaylistFilter.Mine -> it.playlist.owner == null || it.playlist.owner == me
                    PlaylistFilter.Offline -> inDownloadList(it.id, it.offline, it.isFavorite, rules)
                }
            }
        val s = settings.playlistSort
        val reversed = settings.playlistSortReversed
        val sorted = filtered.sortedForPlaylists(s, reversed) { it.sortFields() }
        HomeUiState(true, recent, liked, saved, sorted, all.size, f, s, reversed, q, sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val downloads: StateFlow<DownloadsSummary> = combine(
        library.downloadList,
        library.trackRefs,
        container.offline.songs,
        container.settings.settings,
    ) { list, refs, songs, settings ->
        summarizeDownloads(list, refs, songs, settings.autoDownloadAll)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsSummary())

    init {
        library.syncIfStale()
    }

    fun setFilter(f: PlaylistFilter) { filter.value = f }
    fun setSort(s: PlaylistSort, reversed: Boolean) { viewModelScope.launch { container.settings.setPlaylistSort(s, reversed) } }
    fun setQuery(q: String) { query.value = q }
    fun refresh() { viewModelScope.launch { library.sync() } }
}

private fun PlaylistItem.sortFields() = PlaylistSortFields(
    name = name,
    owner = playlist.owner,
    created = playlist.created,
    changed = playlist.changed,
    lastPlayedAt = lastPlayedAt,
    songCount = playlist.songCount,
    durationSec = playlist.durationSec,
)
