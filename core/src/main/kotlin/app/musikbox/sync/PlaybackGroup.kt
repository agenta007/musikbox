package app.musikbox.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** How a [PlaybackGroup] reaches the other devices. [SyncEngine] in the apps, a fake in tests. */
fun interface RemoteSender {
    /** False when the device couldn't be reached. */
    suspend fun send(peerId: String, command: RemoteCommand): Boolean
}

/**
 * The same music on several devices: this device leads, linked devices follow.
 *
 * The app reports what it plays ([onLocal]) whenever that changes, and about once a second while
 * anyone is linked. Followers are told only what changed: a new queue or track loads it there,
 * play and pause go through as they are, and a jump in position (a seek here) becomes a seek there.
 * Devices that aren't linked aren't touched, so each can keep playing something of its own.
 *
 * The followers keep in step to within the network delay and the time a stream takes to start,
 * which is fine across rooms; it isn't sample-accurate, so two speakers in one room may echo.
 */
class PlaybackGroup(
    private val sender: RemoteSender,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _linked = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the devices following this one. */
    val linked: StateFlow<Set<String>> = _linked.asStateFlow()

    private val mutex = Mutex()
    private var last: NowPlayingInfo? = null
    private var lastAt = 0L

    /** Start [peerId] on what plays here, at the same place, and keep it following. */
    fun link(peerId: String, now: NowPlayingInfo?) {
        last = now; lastAt = clock()  // the starting point: only what changes after this is sent
        _linked.update { it + peerId }
        if (now != null) scope.launch(Dispatchers.IO) { mutex.withLock { sender.send(peerId, load(now)) } }
    }

    /** It stops following, and keeps playing whatever it was playing. */
    fun unlink(peerId: String) = _linked.update { it - peerId }

    fun isLinked(peerId: String) = peerId in _linked.value

    /** What plays here now. Null when nothing does. */
    fun onLocal(now: NowPlayingInfo?) {
        val peers = _linked.value
        if (peers.isEmpty()) {
            last = now; lastAt = clock()
            return
        }
        val commands = diff(last, lastAt, now, clock())
        last = now; lastAt = clock()
        if (commands.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock { // in order: a load must arrive before the pause that follows it
                for (peer in peers) for (c in commands) if (!sender.send(peer, c)) break
            }
        }
    }

    companion object {
        /** A position further than this from where playback should be means someone seeked. */
        const val SEEK_THRESHOLD_MS = 4_000L

        fun load(now: NowPlayingInfo) = RemoteCommand(RemoteCommand.LOAD, queue = now)

        /** Pure: what the followers need to hear to go from [before] to [after]. */
        fun diff(before: NowPlayingInfo?, beforeAt: Long, after: NowPlayingInfo?, afterAt: Long): List<RemoteCommand> {
            if (after == null) return if (before?.playing == true) listOf(RemoteCommand(RemoteCommand.PAUSE)) else emptyList()
            if (before == null || before.queue.map { it.id } != after.queue.map { it.id } || before.index != after.index) {
                return listOf(load(after))
            }
            val commands = mutableListOf<RemoteCommand>()
            val expected = before.positionMs + if (before.playing) afterAt - beforeAt else 0
            if (abs(after.positionMs - expected) > SEEK_THRESHOLD_MS) commands += RemoteCommand(RemoteCommand.SEEK, positionMs = after.positionMs)
            if (after.playing != before.playing) commands += RemoteCommand(if (after.playing) RemoteCommand.PLAY else RemoteCommand.PAUSE)
            return commands
        }
    }
}
