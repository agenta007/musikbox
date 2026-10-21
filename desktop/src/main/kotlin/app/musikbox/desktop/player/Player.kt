package app.musikbox.desktop.player

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.JsonFile
import app.musikbox.desktop.data.Downloads
import app.musikbox.desktop.data.Library
import app.musikbox.desktop.data.SettingsRepo
import app.musikbox.desktop.data.Song
import app.musikbox.desktop.data.StreamCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.min

enum class Repeat { Off, All, One }

data class QItem(val uid: Long, val song: Song)

data class PlayerState(
    val current: QItem? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0L,
    /** In play order (respects shuffle). */
    val queue: List<QItem> = emptyList(),
    val pos: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    val volume: Int = 80,
    val sourcePlaylistId: String? = null,
    val error: String? = null,
) {
    val song get() = current?.song
    val hasNext get() = queue.isNotEmpty() && (pos < queue.lastIndex || repeat == Repeat.All)
    val hasPrevious get() = current != null
}

sealed interface SleepTimer {
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

@Serializable
private data class QueueSnapshot(
    val songs: List<Song>,
    val order: List<Int>,
    val pos: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Repeat,
    val playlistId: String? = null,
)

/**
 * Owns the play queue and drives [Mpv]. All methods must be called on the UI (Main)
 * dispatcher; mpv events are collected there too, so no locking is needed.
 */
class Player(
    private val mpv: Mpv,
    private val api: SubsonicClient,
    private val settings: SettingsRepo,
    private val library: Library,
    private val downloads: Downloads,
    private val cache: StreamCache,
    private val scope: CoroutineScope,
) {
    private var items = listOf<QItem>()
    private var order = listOf<Long>()
    private var pos = -1
    private var nextUid = 1L
    private var shuffle = false
    private var repeat = Repeat.Off
    private var sourcePlaylistId: String? = null

    private var loadedUid: Long? = null
    private var paused = true
    private var idle = true
    private var buffering = false
    private var durationMs = 0L
    private var pendingSeekMs: Long? = null
    private var resumePositionMs = 0L
    private var consecutiveErrors = 0
    private var error: String? = null
    private var lastSavedPosition = 0L

    private val _state = MutableStateFlow(PlayerState(volume = settings.current.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    /** Playback position in ms, in 250 ms steps; kept apart from [state]. */
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

    private val queueStore = JsonFile(AppPaths.queueFile, QueueSnapshot.serializer())
    private var saveJob: Job? = null

    /** Called whenever the track changes (MPRIS listens). */
    var onTrackChanged: (() -> Unit)? = null

    fun start(): Result<Unit> {
        val r = mpv.start(settings.current.volume)
        if (r.isFailure) error = r.exceptionOrNull()?.message
        scope.launch { mpv.events.collect(::onEvent) }
        restore()
        publish()
        return r
    }

    // ---------------------------------------------------------------- mpv events

    /**
     * mpv reports time-pos dozens of times a second and every change redraws the UI, so the
     * published playhead moves in 250 ms steps (the visualizer interpolates in between).
     */
    private fun setPlayhead(ms: Long) {
        val shown = _position.value
        if (ms < shown || ms - shown >= 250) _position.value = ms
    }

    private fun onEvent(e: MpvEvent) {
        when (e) {
            is MpvEvent.Property -> when (e.name) {
                "pause" -> paused = e.value.asBool ?: paused
                "idle-active" -> idle = e.value.asBool ?: idle
                "paused-for-cache" -> buffering = e.value.asBool ?: false
                "duration" -> durationMs = ((e.value.asDouble ?: 0.0) * 1000).toLong()
                "volume" -> e.value.asDouble?.let { v ->
                    val vol = v.toInt()
                    if (vol != _state.value.volume) settings.update { it.copy(volume = vol) }
                }
                "time-pos" -> {
                    e.value.asDouble?.let { setPlayhead((it * 1000).toLong()) }
                    if (kotlin.math.abs(_position.value - lastSavedPosition) > 10_000) scheduleSave()
                    return // hot path: don't republish state
                }
            }
            MpvEvent.FileLoaded -> {
                consecutiveErrors = 0
                pendingSeekMs?.let { mpv.command("seek", it / 1000.0, "absolute") }
                pendingSeekMs = null
            }
            is MpvEvent.EndFile -> when (e.reason) {
                "eof" -> onTrackEnded()
                "error" -> onTrackError(e.error)
            }
            MpvEvent.Died -> {
                loadedUid = null
                paused = true
                idle = true
            }
        }
        publish()
    }

    private fun onTrackEnded() {
        scrobbleLeaving(auto = true)
        loadedUid = null
        if (_sleep.value == SleepTimer.EndOfTrack) {
            _sleep.value = null
            advance(play = false)
            return
        }
        if (repeat == Repeat.One) current()?.let { load(it, play = true) } else advance(play = true)
    }

    private fun onTrackError(message: String?) {
        loadedUid = null
        error = "Couldn't play ${current()?.song?.title ?: "track"}" + (message?.let { ": $it" } ?: "")
        if (consecutiveErrors++ < 10 && hasNextIndex()) advance(play = true) else publish()
    }

    // ---------------------------------------------------------------- queue

    private fun current(): QItem? = order.getOrNull(pos)?.let { uid -> items.firstOrNull { it.uid == uid } }
    private fun hasNextIndex() = order.isNotEmpty() && (pos < order.lastIndex || repeat == Repeat.All)

    private fun newItems(songs: List<Song>) = songs.map { QItem(nextUid++, it) }

    private fun advance(play: Boolean) {
        when {
            pos < order.lastIndex -> pos++
            repeat == Repeat.All && order.isNotEmpty() -> pos = 0
            else -> { // end of queue: rest on the last track, stopped
                loadedUid = null
                paused = true
                _position.value = 0
                resumePositionMs = 0
                publish(); scheduleSave()
                return
            }
        }
        current()?.let { load(it, play) }
    }

    private fun load(item: QItem, play: Boolean, startMs: Long = 0L) {
        if (!mpv.isRunning) {
            mpv.start(settings.current.volume).onFailure { error = it.message; publish(); return }
        }
        val song = item.song
        val br = settings.current.streamBitrate
        val source = downloads.file(song.id)?.absolutePath
            ?: cache.file(song.id, br)?.absolutePath
            ?: api.streamUrl(song.id, br)?.also { cache.fetch(song.id, br, it) }
        if (source == null) {
            error = "Not signed in"; publish(); return
        }
        error = null
        loadedUid = item.uid
        durationMs = song.durationSec * 1000L
        _position.value = startMs
        pendingSeekMs = startMs.takeIf { it > 0 }
        resumePositionMs = 0
        mpv.command("loadfile", source, "replace")
        mpv.setProperty("pause", !play)
        paused = !play
        scrobble(song.id, submission = false)
        publish()
        scheduleSave()
        onTrackChanged?.invoke()
    }

    private fun scrobbleLeaving(auto: Boolean) {
        val item = current() ?: return
        if (loadedUid != item.uid) return
        val duration = item.song.durationSec * 1000L
        val threshold = min(duration / 2, 240_000L)
        if (auto || (duration > 0 && _position.value >= threshold)) scrobble(item.song.id, submission = true)
    }

    private fun scrobble(id: String, submission: Boolean) {
        scope.launch { runCatching { api.scrobble(id, submission) } }
    }

    // ---------------------------------------------------------------- commands

    /** [shuffle] null keeps the current shuffle mode. */
    fun playSongs(songs: List<Song>, startIndex: Int = 0, playlistId: String? = null, shuffle: Boolean? = null) {
        if (songs.isEmpty()) return
        scrobbleLeaving(auto = false)
        shuffle?.let { this.shuffle = it }
        items = newItems(songs)
        val start = if (shuffle == true) items.indices.random() else startIndex.coerceIn(items.indices)
        order = buildOrder(items[start].uid)
        pos = order.indexOf(items[start].uid)
        sourcePlaylistId = playlistId
        consecutiveErrors = 0
        load(items[start], play = true)
        if (playlistId != null) library.markPlayed(playlistId)
    }

    /** Device handoff: take over another device's queue at its position. */
    fun resumeQueue(songs: List<Song>, index: Int, positionMs: Long, playlistId: String?) {
        if (songs.isEmpty()) return
        scrobbleLeaving(auto = false)
        items = newItems(songs)
        val start = index.coerceIn(items.indices)
        order = buildOrder(items[start].uid)
        pos = order.indexOf(items[start].uid)
        sourcePlaylistId = playlistId
        consecutiveErrors = 0
        load(items[start], play = true, startMs = positionMs)
    }

    fun playPlaylist(id: String, shuffle: Boolean? = null, startIndex: Int = 0) =
        playSongs(library.tracksNow(id), startIndex, id, shuffle)

    private fun buildOrder(firstUid: Long): List<Long> {
        val uids = items.map { it.uid }
        return if (shuffle) listOf(firstUid) + (uids - firstUid).shuffled() else uids
    }

    fun playNext(song: Song) {
        val item = newItems(listOf(song)).first()
        if (order.isEmpty()) { playSongs(listOf(song)); return }
        val cur = current()
        val at = cur?.let { c -> items.indexOfFirst { it.uid == c.uid } + 1 } ?: items.size
        items = items.toMutableList().apply { add(at, item) }
        order = order.toMutableList().apply { add(pos + 1, item.uid) }
        publish(); scheduleSave()
    }

    fun enqueue(song: Song) {
        val item = newItems(listOf(song)).first()
        if (order.isEmpty()) { playSongs(listOf(song)); return }
        items = items + item
        order = order + item.uid
        publish(); scheduleSave()
    }

    fun togglePlay() {
        val cur = current() ?: return
        if (loadedUid == null) {
            load(cur, play = true, startMs = resumePositionMs)
        } else {
            paused = !paused
            mpv.setProperty("pause", paused)
            publish()
            if (paused) scheduleSave()
        }
    }

    fun play() { if (loadedUid == null || paused) togglePlay() }
    fun pause() { if (loadedUid != null && !paused) togglePlay() }

    fun stop() {
        mpv.command("stop")
        loadedUid = null
        paused = true
        _position.value = 0
        publish()
    }

    fun next() {
        if (!hasNextIndex()) return
        scrobbleLeaving(auto = false)
        advance(play = true)
    }

    fun previous() {
        val cur = current() ?: return
        if (_position.value > 3_000 || pos == 0 && repeat != Repeat.All) {
            if (loadedUid == null) load(cur, play = true) else seekTo(0)
            return
        }
        scrobbleLeaving(auto = false)
        pos = if (pos > 0) pos - 1 else order.lastIndex
        current()?.let { load(it, play = true) }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        if (loadedUid == null) {
            resumePositionMs = target
            _position.value = target
            return
        }
        mpv.command("seek", target / 1000.0, "absolute")
        _position.value = target
        onTrackChanged?.invoke()
    }

    fun seekBy(deltaMs: Long) = seekTo(_position.value + deltaMs)

    fun skipTo(uid: Long) {
        val i = order.indexOf(uid)
        if (i < 0) return
        scrobbleLeaving(auto = false)
        pos = i
        current()?.let { load(it, play = true) }
    }

    fun remove(uid: Long) {
        val cur = current()
        if (cur?.uid == uid) {
            val wasPlaying = !paused && loadedUid != null
            items = items.filterNot { it.uid == uid }
            order = order.filterNot { it == uid }
            if (order.isEmpty()) { clear(); return }
            pos = pos.coerceAtMost(order.lastIndex)
            current()?.let { load(it, play = wasPlaying) }
        } else {
            items = items.filterNot { it.uid == uid }
            order = order.filterNot { it == uid }
            pos = cur?.let { order.indexOf(it.uid) } ?: -1
            publish(); scheduleSave()
        }
    }

    fun clear() {
        mpv.command("stop")
        items = emptyList(); order = emptyList(); pos = -1
        loadedUid = null; paused = true; sourcePlaylistId = null
        _position.value = 0
        publish()
        scope.launch { queueStore.save(null) }
        onTrackChanged?.invoke()
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        val cur = current()
        if (cur != null) {
            order = buildOrder(cur.uid)
            pos = order.indexOf(cur.uid)
        }
        publish(); scheduleSave()
    }

    fun toggleShuffle() = setShuffle(!shuffle)

    fun setRepeat(r: Repeat) { repeat = r; publish(); scheduleSave() }

    fun cycleRepeat() = setRepeat(
        when (repeat) {
            Repeat.Off -> Repeat.All
            Repeat.All -> Repeat.One
            Repeat.One -> Repeat.Off
        },
    )

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        mpv.setProperty("volume", vol)
        settings.update { it.copy(volume = vol) }
        publish()
    }

    fun dismissError() { error = null; publish() }

    // ---------------------------------------------------------------- sleep timer

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleep.value = SleepTimer.At(System.currentTimeMillis() + minutes * 60_000L)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _sleep.value = null
        }
    }

    fun sleepAtEndOfTrack() { sleepJob?.cancel(); _sleep.value = SleepTimer.EndOfTrack }
    fun cancelSleepTimer() { sleepJob?.cancel(); _sleep.value = null }

    // ---------------------------------------------------------------- state + persistence

    private fun publish() {
        val byUid = items.associateBy { it.uid }
        _state.value = PlayerState(
            current = current(),
            isPlaying = loadedUid != null && !paused && !idle,
            buffering = loadedUid != null && buffering,
            durationMs = durationMs.takeIf { it > 0 } ?: ((current()?.song?.durationSec ?: 0) * 1000L),
            queue = order.mapNotNull { byUid[it] },
            pos = pos,
            shuffle = shuffle,
            repeat = repeat,
            volume = settings.current.volume,
            sourcePlaylistId = sourcePlaylistId,
            error = error,
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            lastSavedPosition = _position.value
            val index = items.withIndex().associate { (i, it) -> it.uid to i }
            queueStore.save(
                if (items.isEmpty()) null else QueueSnapshot(
                    songs = items.map { it.song },
                    order = order.mapNotNull { index[it] },
                    pos = pos,
                    positionMs = _position.value,
                    shuffle = shuffle,
                    repeat = repeat,
                    playlistId = sourcePlaylistId,
                ),
            )
        }
    }

    /** Last session's queue comes back paused at the same spot. */
    private fun restore() {
        val snap = queueStore.load() ?: return
        if (snap.songs.isEmpty()) return
        items = newItems(snap.songs)
        order = snap.order.mapNotNull { items.getOrNull(it)?.uid }.ifEmpty { items.map { it.uid } }
        pos = snap.pos.coerceIn(0, order.lastIndex)
        shuffle = snap.shuffle
        repeat = snap.repeat
        sourcePlaylistId = snap.playlistId
        resumePositionMs = snap.positionMs
        _position.value = snap.positionMs
    }

    fun shutdown() {
        saveJob?.cancel()
        val index = items.withIndex().associate { (i, it) -> it.uid to i }
        queueStore.saveBlocking(
            if (items.isEmpty()) null else QueueSnapshot(
                items.map { it.song }, order.mapNotNull { index[it] }, pos, _position.value, shuffle, repeat, sourcePlaylistId,
            ),
        )
        mpv.stop()
    }
}
