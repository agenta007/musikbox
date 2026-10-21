package app.musikbox.data

import app.musikbox.data.local.MusikboxDatabase
import app.musikbox.data.local.PlaylistItem
import app.musikbox.data.local.PlaylistPrefs
import app.musikbox.data.local.PlaylistTrack
import app.musikbox.data.local.SongEntity
import app.musikbox.data.local.TrackRef
import app.musikbox.sync.PrefRecord
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.SettingsStore
import app.musikbox.playback.OfflineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import app.musikbox.data.local.PlaylistEntity
import app.musikbox.data.settings.downloadRules
import app.musikbox.sync.CloudSync
import kotlinx.coroutines.flow.first

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val message: String) : SyncState
}

data class SearchResults(
    val playlists: List<PlaylistItem>,
    val songs: List<SongEntity>,
    val fromServer: Boolean,
)

/**
 * Single source of truth for playlists. Every playlist and its full track list is mirrored
 * into Room, so the whole library browses instantly and offline; the server is only asked
 * for playlists whose `changed` stamp moved since the last sync.
 */
class LibraryRepository(
    db: MusikboxDatabase,
    private val api: SubsonicClient,
    private val settings: SettingsStore,
    private val offline: OfflineManager,
    private val scope: CoroutineScope,
    private val log: ActivityLog,
) {
    private val playlistDao = db.playlistDao()
    private val trackDao = db.trackDao()
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** Every server playlist (the liked list is separate: see [liked]). */
    val playlists: Flow<List<PlaylistItem>> = playlistDao.observeAll().map { all -> all.filter { it.id != LIKED_PLAYLIST_ID } }
    val recent: Flow<List<PlaylistItem>> = playlistDao.observeRecent(12)
    /** Saved (bookmarked) playlists. */
    val saved: Flow<List<PlaylistItem>> = playlistDao.observeFavorites().map { all -> all.filter { it.id != LIKED_PLAYLIST_ID } }
    /** ⭐ starred songs as a playlist; null until the first sync. */
    val liked: Flow<PlaylistItem?> = playlistDao.observe(LIKED_PLAYLIST_ID)

    val trackRefs: Flow<List<TrackRef>> = trackDao.observeTrackRefs()

    /** Everything kept on the device: chosen playlists plus whatever the download rules cover. */
    val downloadList: Flow<List<PlaylistItem>> =
        combine(playlistDao.observeAll(), settings.settings.map { it.downloadRules }.distinctUntilChanged()) { all, rules ->
            all.filter { inDownloadList(it.id, it.offline, it.isFavorite, rules) }
        }

    fun playlist(id: String): Flow<PlaylistItem?> = playlistDao.observe(id)
    fun tracks(id: String): Flow<List<PlaylistTrack>> = trackDao.observeTracks(id)
    fun song(id: String): Flow<SongEntity?> = trackDao.observeSong(id)
    suspend fun tracksNow(id: String): List<PlaylistTrack> = trackDao.getTracks(id)

    /** Skip if a sync finished within [maxAgeMs] and nothing forces it. */
    fun syncIfStale(maxAgeMs: Long = 5 * 60_000L) {
        if (System.currentTimeMillis() - settings.current.lastSyncAt > maxAgeMs) {
            scope.launch { sync() }
        }
    }

    suspend fun sync(force: Boolean = false): Result<Unit> = syncMutex.withLock {
        if (settings.current.credentials == null) return Result.failure(IllegalStateException("Not signed in"))
        _syncState.value = SyncState.Running
        val result = runCatching {
            val me = settings.current.credentials?.username
            val remote = api.getPlaylists().filterNot { CloudSync.isSyncPlaylist(it, me) }
            val existing = playlistDao.getAll().associateBy { it.id }
            val stale = (existing.keys - remote.map { it.id }.toSet() - LIKED_PLAYLIST_ID).toList()
            if (stale.isNotEmpty()) {
                playlistDao.delete(stale)
                trackDao.clearTracksFor(stale)
                playlistDao.deletePrefs(stale)
            }
            val entities = remote.map { dto ->
                val old = existing[dto.id]
                val e = dto.toEntity(old?.tracksSyncedChanged)
                // Keep the track-sum duration computed in syncTracks while the playlist is unchanged.
                if (old != null && old.changed == e.changed) e.copy(durationSec = maxOf(e.durationSec, old.durationSec)) else e
            }
            playlistDao.upsert(entities)

            val outdated = entities.filter {
                force || it.tracksSyncedChanged == null || it.tracksSyncedChanged != (it.changed ?: "")
            }
            val gate = Semaphore(4)
            coroutineScope {
                outdated.map { p -> async { gate.withPermit { syncTracks(p.id) } } }.awaitAll()
            }
            syncLiked().getOrThrow()
            trackDao.pruneOrphanSongs()
            settings.setLastSync(System.currentTimeMillis())
            log.i(
                "Sync OK — ${remote.size} playlists" +
                    (if (outdated.isNotEmpty()) ", refreshed ${outdated.size}" else ", nothing changed") +
                    (if (stale.isNotEmpty()) ", ${stale.size} removed on server" else ""),
            )
        }
        _syncState.value = result.fold(
            onSuccess = { SyncState.Idle },
            onFailure = { SyncState.Failed(it.message ?: "Sync failed") },
        )
        result.onFailure { log.w("Sync failed — ${it.message ?: it.javaClass.simpleName}") }
        if (result.isSuccess) refreshOfflineDownloads()
        result
    }

    private suspend fun syncTracks(playlistId: String) {
        val dto = api.getPlaylist(playlistId)
        val songs = dto.entry.filterNot { it.isDir }.map { it.toEntity() }
        trackDao.replaceTracks(playlistId, songs)
        // Some servers report a stale or zero playlist duration; the track sum is authoritative.
        val entity = dto.toEntity(tracksSyncedChanged = dto.changed ?: "")
        playlistDao.upsert(listOf(entity.copy(durationSec = maxOf(entity.durationSec, songs.sumOf { it.durationSec.toLong() }))))
    }

    /** Liked list = the server's ⭐ songs, newest first. Cheap: one request. */
    suspend fun syncLiked(): Result<Unit> = runCatching {
        val songs = api.getStarred2().song.filterNot { it.isDir }.sortedByDescending { it.starred ?: "" }.map { it.toEntity() }
        val before = trackDao.getTracks(LIKED_PLAYLIST_ID).map { it.song.id }
        trackDao.replaceLiked(LIKED_PLAYLIST_ID, songs)
        upsertLikedEntity(songs)
        if (songs.map { it.id } != before && inDownloadList(LIKED_PLAYLIST_ID, prefs(LIKED_PLAYLIST_ID).offline, false, settings.current.downloadRules)) {
            reconcileDownloads("liked songs changed")
        }
    }

    private suspend fun upsertLikedEntity(songs: List<SongEntity>) {
        playlistDao.upsert(
            listOf(
                PlaylistEntity(
                    id = LIKED_PLAYLIST_ID, name = LIKED_PLAYLIST_NAME, comment = null, owner = null, isPublic = false,
                    songCount = songs.size, durationSec = songs.sumOf { it.durationSec.toLong() },
                    coverArt = songs.firstOrNull()?.coverArt, created = null, changed = null, tracksSyncedChanged = "",
                ),
            ),
        )
    }

    private suspend fun downloadListIds(): List<String> {
        val rules = settings.current.downloadRules
        return playlistDao.getAllItems().filter { inDownloadList(it.id, it.offline, it.isFavorite, rules) }.map { it.id }
    }

    private suspend fun downloadListSongs(): List<SongEntity> =
        downloadListIds().takeIf { it.isNotEmpty() }?.let { trackDao.songsInPlaylists(it) }.orEmpty()

    /** Queue songs of the download list that aren't on disk yet and retry failed ones. */
    suspend fun refreshOfflineDownloads() {
        val songs = downloadListSongs()
        if (songs.isNotEmpty()) offline.ensureDownloaded(songs, "download list")
    }

    /**
     * After the list's membership changed (rule switched, playlist saved/unsaved, star toggled):
     * download what's newly needed and delete files nothing needs any more.
     */
    suspend fun reconcileDownloads(reason: String) {
        val needed = downloadListSongs()
        val neededIds = needed.map { it.id }.toSet()
        if (needed.isNotEmpty()) offline.ensureDownloaded(needed, reason)
        val unneeded = offline.downloaded.value - neededIds
        if (unneeded.isNotEmpty()) offline.remove(unneeded, reason)
    }

    suspend fun setDownloadRule(all: Boolean? = null, saved: Boolean? = null, liked: Boolean? = null) {
        all?.let { settings.setAutoDownloadAll(it) }
        saved?.let { settings.setAutoDownloadSaved(it) }
        liked?.let { settings.setAutoDownloadLiked(it) }
        // Settings flow updates asynchronously; wait for it so the rules below are current.
        settings.settings.first { s ->
            (all == null || s.autoDownloadAll == all) && (saved == null || s.autoDownloadSaved == saved) && (liked == null || s.autoDownloadLiked == liked)
        }
        log.i(
            "Download rules: " + listOfNotNull(
                all?.let { "all playlists ${if (it) "on" else "off"}" },
                saved?.let { "saved playlists ${if (it) "on" else "off"}" },
                liked?.let { "liked songs ${if (it) "on" else "off"}" },
            ).joinToString(),
        )
        reconcileDownloads("download rules changed")
    }

    // ---- Download list (one-trigger operations over every playlist kept offline) ----

    suspend fun addToDownloadList(playlistIds: List<String>) {
        val now = System.currentTimeMillis()
        playlistIds.forEach { playlistDao.upsertPrefs(prefs(it).copy(offline = true, offlineChangedAt = now)) }
        val names = playlistIds.mapNotNull { playlistDao.get(it)?.name }
        log.i("Added to download list: ${names.joinToString()}")
        resumeIfPaused()
        offline.ensureDownloaded(trackDao.songsInPlaylists(playlistIds), names.joinToString())
    }

    /** Resume + queue everything missing + retry everything failed. */
    suspend fun downloadAll() {
        resumeIfPaused()
        offline.ensureDownloaded(downloadListSongs(), "download list")
        offline.retryFailed("download all")
    }

    /** Delete every downloaded file and fetch the whole list again. */
    suspend fun redownloadAll() {
        resumeIfPaused()
        offline.redownload(downloadListSongs(), "whole download list")
    }

    suspend fun redownloadPlaylist(playlistId: String) {
        resumeIfPaused()
        val name = playlistDao.get(playlistId)?.name ?: playlistId
        offline.redownload(trackDao.getTracks(playlistId).map { it.song }, name)
    }

    /** Free the space but keep the list; downloads stay paused until "Download all". */
    suspend fun deleteDownloadedFiles() {
        setDownloadsPaused(true)
        offline.removeAll("files deleted, download list kept")
    }

    suspend fun setDownloadsPaused(paused: Boolean) {
        settings.setDownloadsPaused(paused)
        offline.setPaused(paused)
    }

    private suspend fun resumeIfPaused() {
        if (offline.status.value.paused) setDownloadsPaused(false)
    }

    private suspend fun prefs(id: String) = playlistDao.getPrefs(id) ?: PlaylistPrefs(id)

    // ---- Device sync ----

    /** Emits whenever any local-only pref changes (favourite, played, download list). */
    val prefsChanges: Flow<List<PlaylistPrefs>> = playlistDao.observePrefs()

    suspend fun syncRecords(): Map<String, PrefRecord> = playlistDao.allPrefs().associate { p ->
        p.playlistId to PrefRecord(
            favorite = p.isFavorite,
            favoriteAt = maxOf(p.favoriteChangedAt, p.favoritedAt ?: 0),
            lastPlayedAt = p.lastPlayedAt ?: 0,
            offline = p.offline,
            offlineAt = p.offlineChangedAt,
        )
    }

    /** Apply records merged from another device (keeping their timestamps), then fix up downloads. */
    suspend fun applySyncedPrefs(changes: Map<String, PrefRecord>) {
        val before = playlistDao.allPrefs().associateBy { it.playlistId }
        playlistDao.upsertPrefs(
            changes.map { (id, r) ->
                val old = before[id] ?: PlaylistPrefs(id)
                old.copy(
                    isFavorite = r.favorite,
                    favoritedAt = if (r.favorite) old.favoritedAt ?: r.favoriteAt else null,
                    favoriteChangedAt = r.favoriteAt,
                    lastPlayedAt = r.lastPlayedAt.takeIf { it > 0 } ?: old.lastPlayedAt,
                    offline = r.offline,
                    offlineChangedAt = r.offlineAt,
                )
            },
        )
        // Saved / download-list changes can change what belongs on disk.
        val membership = changes.any { (id, r) ->
            before[id]?.offline != r.offline || (before[id]?.isFavorite ?: false) != r.favorite
        }
        if (membership) reconcileDownloads("changes from another device")
    }

    suspend fun markPlayed(playlistId: String) {
        playlistDao.upsertPrefs(prefs(playlistId).copy(lastPlayedAt = System.currentTimeMillis()))
    }

    suspend fun toggleFavorite(playlistId: String) {
        val p = prefs(playlistId)
        val fav = !p.isFavorite
        val now = System.currentTimeMillis()
        playlistDao.upsertPrefs(p.copy(isFavorite = fav, favoritedAt = if (fav) now else null, favoriteChangedAt = now))
        if (settings.current.autoDownloadSaved) reconcileDownloads(if (fav) "playlist saved" else "playlist unsaved")
    }

    suspend fun setOffline(playlistId: String, enabled: Boolean) {
        if (enabled) {
            addToDownloadList(listOf(playlistId))
            return
        }
        playlistDao.upsertPrefs(prefs(playlistId).copy(offline = false, offlineChangedAt = System.currentTimeMillis()))
        val name = playlistDao.get(playlistId)?.name ?: playlistId
        log.i("Removed from download list: $name")
        val songs = trackDao.getTracks(playlistId).map { it.song }
        val stillNeeded = downloadListSongs().map { it.id }.toSet()
        offline.remove(songs.map { it.id }.filter { it !in stillNeeded }, name)
    }

    /** Empty the download list and delete every file. */
    suspend fun removeAllDownloads() {
        playlistDao.clearOffline(System.currentTimeMillis())
        settings.setAutoDownloadAll(false)
        settings.setAutoDownloadSaved(false)
        settings.setAutoDownloadLiked(false)
        settings.setDownloadsPaused(false)
        offline.setPaused(false)
        offline.removeAll("download list cleared")
    }

    /** ⭐ a song: updates the liked list instantly, then the server (reverted if that fails). */
    suspend fun toggleStar(song: SongEntity): Result<Unit> {
        val target = !song.starred
        val likedBefore = trackDao.getTracks(LIKED_PLAYLIST_ID).map { it.song }
        fun likedWith(starred: Boolean) =
            if (starred) listOf(song.copy(starred = true)) + likedBefore.filter { it.id != song.id } else likedBefore.filter { it.id != song.id }
        trackDao.setStarred(song.id, target)
        likedWith(target).let { trackDao.replaceTracks(LIKED_PLAYLIST_ID, it); upsertLikedEntity(it) }
        return runCatching { if (target) api.star(song.id) else api.unstar(song.id) }
            .onFailure {
                trackDao.setStarred(song.id, !target)
                trackDao.replaceTracks(LIKED_PLAYLIST_ID, likedBefore); upsertLikedEntity(likedBefore)
            }
            .onSuccess {
                if (inDownloadList(LIKED_PLAYLIST_ID, prefs(LIKED_PLAYLIST_ID).offline, false, settings.current.downloadRules)) {
                    reconcileDownloads(if (target) "song liked" else "song unliked")
                }
            }
    }

    suspend fun search(query: String): SearchResults {
        val playlists = playlistDao.search(query)
        return runCatching { api.search(query).map { it.toEntity() } }
            .map { SearchResults(playlists, it, fromServer = true) }
            .getOrElse { SearchResults(playlists, trackDao.searchLocal(query), fromServer = false) }
    }

    // ---- Playlist editing (server first, then re-mirror) ----

    suspend fun createPlaylist(name: String, songIds: List<String>): Result<Unit> = runCatching {
        api.createPlaylist(name, songIds)
    }.onSuccess { sync() }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>): Result<Unit> = runCatching {
        api.updatePlaylist(playlistId, addSongIds = songIds)
        syncTracks(playlistId)
        val p = prefs(playlistId)
        if (inDownloadList(playlistId, p.offline, p.isFavorite, settings.current.downloadRules)) {
            offline.ensureDownloaded(trackDao.getTracks(playlistId).map { it.song }, "new songs in playlist")
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, position: Int): Result<Unit> = runCatching {
        api.updatePlaylist(playlistId, removeIndexes = listOf(position))
        syncTracks(playlistId)
    }

    suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit> = runCatching {
        api.updatePlaylist(playlistId, name = name)
        syncTracks(playlistId)
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = runCatching {
        setOffline(playlistId, false)
        api.deletePlaylist(playlistId)
        playlistDao.delete(listOf(playlistId))
        trackDao.clearTracks(playlistId)
        playlistDao.deletePrefs(listOf(playlistId))
    }

    fun coverUrl(coverArt: String?, size: Int = 600) = api.coverArtUrl(coverArt, size)

}
