package app.musikbox.ui.downloads

import app.musikbox.data.local.PlaylistItem
import app.musikbox.data.local.TrackRef
import app.musikbox.playback.SongDlState
import app.musikbox.playback.SongDownload

data class PlaylistDownload(
    val item: PlaylistItem,
    val downloaded: Int,
    val total: Int,
    val pending: Int,
    val failed: Int,
    val bytes: Long,
) {
    val complete get() = total > 0 && downloaded >= total
    val fraction get() = if (total == 0) 0f else downloaded / total.toFloat()
}

data class DownloadsSummary(
    /** One entry per playlist in the download list (every playlist in "download all" mode). */
    val playlists: List<PlaylistDownload> = emptyList(),
    val autoAll: Boolean = false,
    val downloadedSongs: Int = 0,
    val totalSongs: Int = 0,
    val pendingSongs: Int = 0,
    val failedSongs: Int = 0,
    val bytes: Long = 0L,
) {
    val complete get() = totalSongs > 0 && downloadedSongs >= totalSongs
    val fraction get() = if (totalSongs == 0) 0f else downloadedSongs / totalSongs.toFloat()
}

/** Folds the download list, track membership and per-song download state into progress numbers. */
fun summarizeDownloads(
    list: List<PlaylistItem>,
    refs: List<TrackRef>,
    songs: Map<String, SongDownload>,
    autoAll: Boolean,
): DownloadsSummary {
    val byPlaylist = refs.groupBy { it.playlistId }
    fun bytesOf(r: TrackRef) = songs[r.songId]?.bytes?.takeIf { it > 0 } ?: r.sizeBytes ?: 0L
    fun state(id: String) = songs[id]?.state

    val entries = list.map { p ->
        val tracks = byPlaylist[p.id].orEmpty().distinctBy { it.songId }
        PlaylistDownload(
            item = p,
            downloaded = tracks.count { state(it.songId) == SongDlState.Completed },
            total = tracks.size,
            pending = tracks.count { state(it.songId) == SongDlState.Queued || state(it.songId) == SongDlState.Downloading },
            failed = tracks.count { state(it.songId) == SongDlState.Failed },
            bytes = tracks.filter { state(it.songId) == SongDlState.Completed }.sumOf(::bytesOf),
        )
    }
    // Songs shared between playlists count once in the totals.
    val unique = list.flatMap { byPlaylist[it.id].orEmpty() }.associateBy { it.songId }
    return DownloadsSummary(
        playlists = entries.sortedWith(compareBy({ it.complete }, { it.item.name.lowercase() })),
        autoAll = autoAll,
        downloadedSongs = unique.keys.count { state(it) == SongDlState.Completed },
        totalSongs = unique.size,
        pendingSongs = unique.keys.count { state(it) == SongDlState.Queued || state(it) == SongDlState.Downloading },
        failedSongs = unique.keys.count { state(it) == SongDlState.Failed },
        bytes = unique.values.filter { state(it.songId) == SongDlState.Completed }.sumOf(::bytesOf),
    )
}
