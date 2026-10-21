package app.musikbox.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musikbox.AppContainer
import app.musikbox.data.local.PlaylistItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library
    private val offline = container.offline

    val summary: StateFlow<DownloadsSummary> = combine(
        library.downloadList,
        library.trackRefs,
        offline.songs,
        container.settings.settings,
    ) { list, refs, songs, settings ->
        summarizeDownloads(list, refs, songs, settings.autoDownloadAll)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsSummary())

    /** Playlists that can still be added to the download list. */
    val addable: StateFlow<List<PlaylistItem>> = combine(library.playlists, library.downloadList) { all, list ->
        val inList = list.map { it.id }.toSet()
        all.filter { it.id !in inList }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status = offline.status
    val songs = offline.songs
    val log = container.log.entries

    // Long-running operations go to the app scope so leaving the screen doesn't cancel them.
    private fun launch(block: suspend () -> Unit) { container.scope.launch { block() } }

    fun togglePause() = launch { library.setDownloadsPaused(!offline.status.value.paused) }
    fun retryFailed() = launch { offline.retryFailed("manual retry") }
    fun downloadAll() = launch { library.downloadAll() }
    fun redownloadAll() = launch { library.redownloadAll() }
    fun deleteFiles() = launch { library.deleteDownloadedFiles() }
    fun clearList() = launch { library.removeAllDownloads() }
    fun add(ids: List<String>) = launch { library.addToDownloadList(ids) }
    fun redownload(playlistId: String) = launch { library.redownloadPlaylist(playlistId) }
    fun removeFromList(playlistId: String) = launch { library.setOffline(playlistId, false) }
    fun clearLog() = container.log.clear()
    fun logText(verbose: Boolean) = container.log.asText(includeDebug = verbose)

    fun tracks(playlistId: String) = library.tracks(playlistId)

    init {
        viewModelScope.launch { offline.awaitReady() }
    }
}
