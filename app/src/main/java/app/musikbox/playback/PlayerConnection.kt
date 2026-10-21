package app.musikbox.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.musikbox.data.LibraryRepository
import app.musikbox.data.local.SongEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class QueueEntry(val index: Int, val song: SongEntity)

data class PlayerUiState(
    val connected: Boolean = false,
    val song: SongEntity? = null,
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0L,
    /** In playback order (respects shuffle). */
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val hasNext: Boolean = false,
    val sourcePlaylistId: String? = null,
) {
    val hasMedia get() = song != null
}

sealed interface SleepTimer {
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/**
 * App-wide bridge to [PlaybackService] through a [MediaController]. UI never touches the
 * player directly; it observes [state] and calls these commands.
 */
class PlayerConnection(
    private val context: Context,
    private val library: LibraryRepository,
    private val queueStore: QueueStore,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var restoreAttempted = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null
    private var saveJob: Job? = null

    val positionMs: Long get() = controller?.currentPosition ?: 0L
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
            refresh(rebuildQueue = queueChanged)
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                )
            ) scheduleSave()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleepTimer.value == SleepTimer.EndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                cancelSleepTimer()
            }
        }
    }

    fun connect() {
        if (controller != null || connecting != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@PlayerConnection.controller = null
                    _state.value = _state.value.copy(connected = false, isPlaying = false)
                }
            })
            .buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh(rebuildQueue = true)
            if (c.mediaItemCount == 0 && !restoreAttempted) {
                restoreAttempted = true
                scope.launch { restore(c) }
            }
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) block(c) else {
            pending += block
            connect()
        }
    }

    private suspend fun restore(c: MediaController) {
        val snap = queueStore.load() ?: return
        if (c.mediaItemCount != 0) return // user started something meanwhile
        val items = snap.songs.map { it.toMediaItem(library.coverUrl(it.coverArt), snap.playlistId) }
        c.setMediaItems(items, snap.index.coerceIn(0, items.lastIndex), snap.positionMs)
    }

    // ---- Commands ----

    /** [shuffle] null keeps the current shuffle mode. */
    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0, playlistId: String? = null, shuffle: Boolean? = null) {
        if (songs.isEmpty()) return
        val items = songs.map { it.toMediaItem(library.coverUrl(it.coverArt), playlistId) }
        withController { c ->
            shuffle?.let { c.shuffleModeEnabled = it }
            val start = if (shuffle == true) Random.nextInt(items.size) else startIndex.coerceIn(0, items.lastIndex)
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
        if (playlistId != null) scope.launch { library.markPlayed(playlistId) }
    }

    fun playPlaylist(playlistId: String, shuffle: Boolean? = null, startIndex: Int = 0) {
        scope.launch {
            val songs = library.tracksNow(playlistId).map { it.song }
            playSongs(songs, startIndex, playlistId, shuffle)
        }
    }

    fun playNext(song: SongEntity) = withController { c ->
        val item = song.toMediaItem(library.coverUrl(song.coverArt), null)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, item)
        }
    }

    fun enqueue(song: SongEntity) = withController { c ->
        val item = song.toMediaItem(library.coverUrl(song.coverArt), null)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else {
            c.addMediaItem(item)
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun pause() = withController { it.pause() }

    /** Device handoff: take over another device's queue at its position. */
    fun resumeQueue(songs: List<SongEntity>, index: Int, positionMs: Long, playlistId: String?) {
        if (songs.isEmpty()) return
        val items = songs.map { it.toMediaItem(library.coverUrl(it.coverArt), playlistId) }
        withController { c ->
            c.setMediaItems(items, index.coerceIn(0, items.lastIndex), positionMs)
            c.prepare()
            c.play()
        }
    }

    /** Snapshot for other devices ("continue here"). Must be called on the main thread. */
    fun snapshot(): Triple<List<SongEntity>, Int, Long>? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        val songs = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).song() }
        if (songs.size != c.mediaItemCount) return null
        return Triple(songs, c.currentMediaItemIndex, c.currentPosition)
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { c ->
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun clearQueue() = withController { c ->
        c.stop(); c.clearMediaItems()
        scope.launch { queueStore.save(null) }
    }

    // ---- Sleep timer ----

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimer.value = SleepTimer.At(endsAt)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _sleepTimer.value = null
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimer.EndOfTrack
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _sleepTimer.value = null
    }

    // ---- State ----

    private fun refresh(rebuildQueue: Boolean) {
        val c = controller ?: return
        val item = c.currentMediaItem
        val song = item?.song()
        val queue = if (rebuildQueue) buildQueue(c) else _state.value.queue
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: (song?.durationSec?.times(1000L) ?: 0L)
        _state.value = PlayerUiState(
            connected = true,
            song = song,
            artworkUrl = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            durationMs = duration,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            hasNext = c.hasNextMediaItem(),
            sourcePlaylistId = item?.sourcePlaylistId(),
        )
    }

    private fun buildQueue(c: MediaController): List<QueueEntry> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val out = ArrayList<QueueEntry>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            c.getMediaItemAt(i).song()?.let { out += QueueEntry(i, it) }
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            val c = controller ?: return@launch
            if (c.mediaItemCount == 0) return@launch
            val songs = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).song() }
            if (songs.size != c.mediaItemCount) return@launch
            queueStore.save(
                QueueSnapshot(songs, c.currentMediaItemIndex, c.currentPosition, c.currentMediaItem?.sourcePlaylistId()),
            )
        }
    }
}
