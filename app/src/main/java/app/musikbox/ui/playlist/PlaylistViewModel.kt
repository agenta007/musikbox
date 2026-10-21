package app.musikbox.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musikbox.AppContainer
import app.musikbox.data.local.PlaylistItem
import app.musikbox.data.local.PlaylistTrack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.autoReason
import app.musikbox.data.inDownloadList
import app.musikbox.data.settings.downloadRules

data class PlaylistUiState(
    val loaded: Boolean = false,
    val item: PlaylistItem? = null,
    val tracks: List<PlaylistTrack> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val downloadedCount: Int = 0,
    val pendingCount: Int = 0,
    val editable: Boolean = false,
    val offline: Boolean = false,
    /** Set when a download rule (all / saved / liked) keeps this playlist on disk. */
    val autoReason: String? = null,
    val isLiked: Boolean = false,
) {
    val downloadComplete get() = offline && tracks.isNotEmpty() && downloadedCount >= tracks.size
}

class PlaylistViewModel(private val container: AppContainer, val playlistId: String) : ViewModel() {
    private val library = container.library

    val state: StateFlow<PlaylistUiState> = combine(
        library.playlist(playlistId),
        library.tracks(playlistId),
        container.offline.downloaded,
        container.offline.pending,
        container.settings.settings,
    ) { item, tracks, downloaded, pending, settings ->
        val ids = tracks.map { it.song.id }
        PlaylistUiState(
            loaded = true,
            item = item,
            tracks = tracks,
            downloaded = downloaded,
            downloadedCount = ids.count { it in downloaded },
            pendingCount = ids.count { it in pending },
            editable = item != null && playlistId != LIKED_PLAYLIST_ID &&
                (item.playlist.owner == null || item.playlist.owner == settings.credentials?.username),
            offline = item != null && inDownloadList(playlistId, item.offline, item.isFavorite, settings.downloadRules),
            autoReason = item?.let { autoReason(playlistId, it.isFavorite, settings.downloadRules) },
            isLiked = playlistId == LIKED_PLAYLIST_ID,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState())

    fun play(startIndex: Int = 0, shuffle: Boolean? = null) {
        val songs = state.value.tracks.map { it.song }
        container.player.playSongs(songs, startIndex, playlistId, shuffle)
    }

    fun toggleFavorite() = viewModelScope.launch { library.toggleFavorite(playlistId) }

    fun setOffline(enabled: Boolean) = container.scope.launch { library.setOffline(playlistId, enabled) }

    suspend fun rename(name: String) = library.renamePlaylist(playlistId, name)

    suspend fun delete() = library.deletePlaylist(playlistId)

    suspend fun removeTrack(position: Int) = library.removeFromPlaylist(playlistId, position)
}
