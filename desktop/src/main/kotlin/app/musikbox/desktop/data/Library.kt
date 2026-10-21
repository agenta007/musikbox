package app.musikbox.desktop.data

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.JsonFile
import app.musikbox.sync.PrefRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import app.musikbox.data.DownloadRules
import app.musikbox.data.LIKED_PLAYLIST_ID
import app.musikbox.data.LIKED_PLAYLIST_NAME
import app.musikbox.data.inDownloadList
import app.musikbox.sync.CloudSync

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val message: String) : SyncState
}

data class SearchResults(val playlists: List<PlaylistItem>, val songs: List<Song>, val fromServer: Boolean)

/**
 * Mirror of every playlist and its tracks, persisted to library.json so the app opens
 * instantly and browses offline. Same incremental sync as the Android app: only playlists
 * whose server `changed` stamp moved are re-fetched.
 */
class Library(
    private val api: SubsonicClient,
    private val settings: SettingsRepo,
    private val downloads: Downloads,
    private val scope: CoroutineScope,
) {
    private val store = JsonFile(AppPaths.libraryFile, LibraryData.serializer())
    private val data = MutableStateFlow(store.load() ?: LibraryData())
    private val syncMutex = Mutex()
    private var saveJob: Job? = null

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private fun LibraryData.items() =
        playlists.values.map { PlaylistItem(it, prefs[it.id] ?: Prefs()) }.sortedBy { it.name.lowercase() }

    /** Every playlist including the liked list (internal; UI uses [playlists] / [liked]). */
    private val allItems: StateFlow<List<PlaylistItem>> =
        data.map { it.items() }.stateIn(scope, SharingStarted.Eagerly, data.value.items())

    /** Every server playlist (the liked list is separate: see [liked]). */
    val playlists: StateFlow<List<PlaylistItem>> = allItems
        .map { all -> all.filter { it.id != LIKED_PLAYLIST_ID } }
        .stateIn(scope, SharingStarted.Eagerly, data.value.items().filter { it.id != LIKED_PLAYLIST_ID })

    /** ⭐ starred songs as a playlist; null until the first sync. */
    val liked: StateFlow<PlaylistItem?> = allItems
        .map { all -> all.firstOrNull { it.id == LIKED_PLAYLIST_ID } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val recent: StateFlow<List<PlaylistItem>> = allItems
        .map { all -> all.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }.take(12) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Saved (bookmarked) playlists. */
    val saved: StateFlow<List<PlaylistItem>> = playlists
        .map { all -> all.filter { it.isFavorite }.sortedByDescending { it.prefs.favoritedAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun playlist(id: String): Flow<PlaylistItem?> =
        data.map { d -> d.playlists[id]?.let { PlaylistItem(it, d.prefs[id] ?: Prefs()) } }.distinctUntilChanged()

    fun tracks(id: String): Flow<List<Song>> = data.map { it.tracksOf(id) }.distinctUntilChanged()
    fun tracksNow(id: String): List<Song> = data.value.tracksOf(id)
    fun song(id: String): Flow<Song?> = data.map { it.songs[id] }.distinctUntilChanged()

    /** (playlistId → its songs) for computing download progress. */
    val allTracks: StateFlow<Map<String, List<Song>>> = data
        .map { d -> d.playlists.keys.associateWith { d.tracksOf(it) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun LibraryData.tracksOf(id: String) = playlists[id]?.trackIds?.mapNotNull { songs[it] }.orEmpty()

    private fun mutate(block: (LibraryData) -> LibraryData) {
        data.update(block)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            store.save(data.value)
        }
    }

    fun syncIfStale(maxAgeMs: Long = 5 * 60_000L) {
        if (System.currentTimeMillis() - settings.current.lastSyncAt > maxAgeMs) scope.launch { sync() }
    }

    suspend fun sync(force: Boolean = false): Result<Unit> = syncMutex.withLock {
        if (settings.current.credentials == null) return Result.failure(IllegalStateException("Not signed in"))
        _syncState.value = SyncState.Running
        val result = runCatching {
            val me = settings.current.credentials?.username
            val remote = api.getPlaylists().filterNot { CloudSync.isSyncPlaylist(it, me) }
            val remoteIds = remote.map { it.id }.toSet() + LIKED_PLAYLIST_ID
            mutate { d ->
                val kept = d.playlists.filterKeys { it in remoteIds }
                val merged = remote.associate { dto ->
                    val old = kept[dto.id]
                    val fresh = dto.toPlaylist(old?.tracksSyncedChanged, old?.trackIds.orEmpty())
                    // Keep the track-sum duration while the playlist is unchanged on the server.
                    dto.id to if (old != null && old.changed == fresh.changed) {
                        fresh.copy(durationSec = maxOf(fresh.durationSec, old.durationSec))
                    } else fresh
                }
                d.copy(playlists = merged + kept.filterKeys { it == LIKED_PLAYLIST_ID }, prefs = d.prefs.filterKeys { it in remoteIds })
            }
            val outdated = data.value.playlists.values.filter {
                force || it.tracksSyncedChanged == null || it.tracksSyncedChanged != (it.changed ?: "")
            }
            val gate = Semaphore(4)
            coroutineScope { outdated.filter { it.id != LIKED_PLAYLIST_ID }.map { p -> async { gate.withPermit { syncTracks(p.id) } } }.awaitAll() }
            syncLiked().getOrThrow()
            mutate { d ->
                val used = d.playlists.values.flatMap { it.trackIds }.toSet()
                d.copy(songs = d.songs.filterKeys { it in used })
            }
            settings.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
        }
        _syncState.value = result.fold({ SyncState.Idle }, { SyncState.Failed(it.message ?: "Sync failed") })
        if (result.isSuccess) refreshOfflineDownloads()
        result
    }

    private suspend fun syncTracks(playlistId: String) {
        val dto = api.getPlaylist(playlistId)
        val songs = dto.entry.filterNot { it.isDir }.map { it.toSong() }
        val total = songs.sumOf { it.durationSec.toLong() }
        val playlist = dto.toPlaylist(dto.changed ?: "", songs.map { it.id })
        mutate { d ->
            d.copy(
                playlists = d.playlists + (playlistId to playlist.copy(durationSec = maxOf(playlist.durationSec, total))),
                songs = d.songs + songs.associateBy { it.id },
            )
        }
    }

    /** Liked list = the server's ⭐ songs, newest first. One request. */
    suspend fun syncLiked(): Result<Unit> = runCatching {
        val songs = api.getStarred2().song.filterNot { it.isDir }.sortedByDescending { it.starred ?: "" }.map { it.toSong().copy(starred = true) }
        val before = data.value.playlists[LIKED_PLAYLIST_ID]?.trackIds
        setLiked(songs)
        if (before != songs.map { it.id } && inList(LIKED_PLAYLIST_ID)) reconcileDownloads()
    }

    private fun setLiked(songs: List<Song>) = mutate { d ->
        val ids = songs.map { it.id }.toSet()
        val unstarred = d.songs.mapValues { (id, s) -> if (s.starred && id !in ids) s.copy(starred = false) else s }
        d.copy(
            songs = unstarred + songs.associateBy { it.id },
            playlists = d.playlists + (LIKED_PLAYLIST_ID to Playlist(
                id = LIKED_PLAYLIST_ID, name = LIKED_PLAYLIST_NAME, songCount = songs.size,
                durationSec = songs.sumOf { it.durationSec.toLong() }, coverArt = songs.firstOrNull()?.coverArt,
                tracksSyncedChanged = "", trackIds = songs.map { it.id },
            )),
        )
    }

    val rules: DownloadRules get() = settings.current.let { DownloadRules(it.autoDownloadAll, it.autoDownloadSaved, it.autoDownloadLiked) }

    fun inList(id: String): Boolean {
        val p = data.value.prefs[id] ?: Prefs()
        return inDownloadList(id, p.offline, p.isFavorite, rules)
    }

    /** Playlists whose songs belong on disk (chosen, or covered by a download rule). */
    fun downloadListIds(): List<String> = data.value.playlists.keys.filter(::inList)

    fun refreshOfflineDownloads() {
        val d = data.value
        downloads.ensure(downloadListIds().flatMap { d.tracksOf(it) }.distinctBy { it.id })
    }

    /** Download what's newly needed, delete files nothing needs any more. */
    fun reconcileDownloads() {
        val d = data.value
        val needed = downloadListIds().flatMap { d.tracksOf(it) }.distinctBy { it.id }
        downloads.ensure(needed)
        val neededIds = needed.map { it.id }.toSet()
        val unneeded = downloads.downloaded.value - neededIds
        if (unneeded.isNotEmpty()) downloads.remove(unneeded)
    }

    fun setDownloadRule(all: Boolean? = null, saved: Boolean? = null, liked: Boolean? = null) {
        settings.update { s ->
            s.copy(
                autoDownloadAll = all ?: s.autoDownloadAll,
                autoDownloadSaved = saved ?: s.autoDownloadSaved,
                autoDownloadLiked = liked ?: s.autoDownloadLiked,
            )
        }
        reconcileDownloads()
    }

    private fun updatePrefs(id: String, block: (Prefs) -> Prefs) =
        mutate { d -> d.copy(prefs = d.prefs + (id to block(d.prefs[id] ?: Prefs()))) }

    fun markPlayed(id: String) = updatePrefs(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }

    fun toggleFavorite(id: String) = updatePrefs(id) {
        val fav = !it.isFavorite
        val now = System.currentTimeMillis()
        it.copy(isFavorite = fav, favoritedAt = if (fav) now else null, favoriteChangedAt = now)
    }.also { if (settings.current.autoDownloadSaved) reconcileDownloads() }

    /** Local-only prefs as seen by device sync. */
    val prefsFlow: Flow<Map<String, Prefs>> = data.map { it.prefs }.distinctUntilChanged()

    fun syncRecords(): Map<String, PrefRecord> = data.value.prefs.mapValues { (_, p) ->
        PrefRecord(
            favorite = p.isFavorite,
            favoriteAt = maxOf(p.favoriteChangedAt, p.favoritedAt ?: 0),
            lastPlayedAt = p.lastPlayedAt ?: 0,
            offline = p.offline,
            offlineAt = p.offlineChangedAt,
        )
    }

    /** Apply merged records from another device, keeping their timestamps, then reconcile downloads. */
    fun applySyncedPrefs(changes: Map<String, PrefRecord>) {
        val before = data.value.prefs
        mutate { d ->
            d.copy(prefs = d.prefs + changes.mapValues { (id, r) ->
                val old = d.prefs[id] ?: Prefs()
                old.copy(
                    isFavorite = r.favorite,
                    favoritedAt = if (r.favorite) old.favoritedAt ?: r.favoriteAt else null,
                    favoriteChangedAt = r.favoriteAt,
                    lastPlayedAt = r.lastPlayedAt.takeIf { it > 0 } ?: old.lastPlayedAt,
                    offline = r.offline,
                    offlineChangedAt = r.offlineAt,
                )
            })
        }
        val membership = changes.any { (id, r) ->
            before[id]?.offline != r.offline || (before[id]?.isFavorite ?: false) != r.favorite
        }
        if (membership) reconcileDownloads()
    }

    fun setOffline(id: String, enabled: Boolean) {
        updatePrefs(id) { it.copy(offline = enabled, offlineChangedAt = System.currentTimeMillis()) }
        val songs = tracksNow(id)
        if (enabled) {
            downloads.ensure(songs)
        } else {
            val d = data.value
            val stillNeeded = downloadListIds().flatMap { d.playlists[it]?.trackIds.orEmpty() }.toSet()
            downloads.remove(songs.map { it.id }.filter { it !in stillNeeded })
        }
    }

    fun removeAllDownloads() {
        val now = System.currentTimeMillis()
        mutate { d -> d.copy(prefs = d.prefs.mapValues { if (it.value.offline) it.value.copy(offline = false, offlineChangedAt = now) else it.value }) }
        settings.update { it.copy(autoDownloadAll = false, autoDownloadSaved = false, autoDownloadLiked = false) }
        downloads.removeAll()
    }

    /** ⭐ a song: updates the liked list instantly, then the server (reverted if that fails). */
    suspend fun toggleStar(song: Song): Result<Unit> {
        val target = !song.starred
        val likedBefore = tracksNow(LIKED_PLAYLIST_ID)
        fun set(v: Boolean) {
            val others = likedBefore.filter { it.id != song.id }
            setLiked(if (v) listOf(song.copy(starred = true)) + others else others)
            if (!v) mutate { d -> d.songs[song.id]?.let { d.copy(songs = d.songs + (song.id to it.copy(starred = false))) } ?: d }
        }
        set(target)
        return runCatching { if (target) api.star(song.id) else api.unstar(song.id) }
            .onFailure { setLiked(likedBefore) }
            .onSuccess { if (inList(LIKED_PLAYLIST_ID)) reconcileDownloads() }
    }

    suspend fun search(query: String): SearchResults {
        val q = query.trim()
        val lists = playlists.value.filter { it.name.contains(q, ignoreCase = true) }
        return runCatching { api.search(q).map { it.toSong() } }
            .map { SearchResults(lists, it, fromServer = true) }
            .getOrElse {
                val local = data.value.songs.values.filter {
                    it.title.contains(q, true) || it.artist?.contains(q, true) == true || it.album?.contains(q, true) == true
                }.sortedBy { it.title.lowercase() }.take(60)
                SearchResults(lists, local, fromServer = false)
            }
    }

    // ---- Editing: server first, then re-mirror ----

    suspend fun createPlaylist(name: String, songIds: List<String>) =
        runCatching { api.createPlaylist(name, songIds) }.onSuccess { sync() }

    suspend fun addToPlaylist(id: String, songIds: List<String>) = runCatching {
        api.updatePlaylist(id, addSongIds = songIds)
        syncTracks(id)
        if (inList(id)) downloads.ensure(tracksNow(id))
    }

    suspend fun removeFromPlaylist(id: String, position: Int) = runCatching {
        api.updatePlaylist(id, removeIndexes = listOf(position))
        syncTracks(id)
    }

    suspend fun renamePlaylist(id: String, name: String) = runCatching {
        api.updatePlaylist(id, name = name)
        syncTracks(id)
    }

    suspend fun deletePlaylist(id: String) = runCatching {
        setOffline(id, false)
        api.deletePlaylist(id)
        mutate { d -> d.copy(playlists = d.playlists - id, prefs = d.prefs - id) }
    }

    fun clear() {
        data.value = LibraryData()
        scope.launch { store.save(null) }
    }

    fun coverUrl(coverArt: String?, size: Int = 600) = api.coverArtUrl(coverArt, size)
}
