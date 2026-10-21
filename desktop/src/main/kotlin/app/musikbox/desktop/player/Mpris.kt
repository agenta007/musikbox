@file:Suppress("FunctionName")

package app.musikbox.desktop.player

import app.musikbox.desktop.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

private const val PATH = "/org/mpris/MediaPlayer2"
private const val IFACE_ROOT = "org.mpris.MediaPlayer2"
private const val IFACE_PLAYER = "org.mpris.MediaPlayer2.Player"

@DBusInterfaceName(IFACE_ROOT)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(IFACE_PLAYER)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * MPRIS 2 on the session bus as `org.mpris.MediaPlayer2.musikbox`: media keys, the Plasma
 * media controller, lock-screen controls, KDE Connect and `playerctl` all drive the player.
 * D-Bus calls arrive on dbus-java threads and are marshalled onto the UI dispatcher.
 */
class Mpris(
    private val player: Player,
    private val library: Library,
    private val scope: CoroutineScope,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
) : MediaPlayer2, MediaPlayer2Player, Properties {

    private var conn: DBusConnection? = null

    fun start(): Result<Unit> = runCatching {
        val c = DBusConnectionBuilder.forSessionBus().build()
        c.requestBusName("org.mpris.MediaPlayer2.musikbox")
        c.exportObject(PATH, this)
        conn = c
        scope.launch {
            player.state
                .map { s -> Snapshot(s.song?.id, s.current?.uid, s.isPlaying, s.current != null, s.shuffle, s.repeat, s.volume, s.hasNext) }
                .distinctUntilChanged()
                .collect { emitChanged() }
        }
    }

    private data class Snapshot(
        val songId: String?, val uid: Long?, val playing: Boolean, val loaded: Boolean,
        val shuffle: Boolean, val repeat: Repeat, val volume: Int, val hasNext: Boolean,
    )

    fun stop() {
        runCatching { conn?.releaseBusName("org.mpris.MediaPlayer2.musikbox") }
        runCatching { conn?.close() }
        conn = null
    }

    private fun emitChanged() {
        val c = conn ?: return
        runCatching {
            c.sendMessage(Properties.PropertiesChanged(PATH, IFACE_PLAYER, playerProps().filterKeys { it != "Position" }, emptyList()))
        }
    }

    private fun onUi(block: () -> Unit) {
        scope.launch { block() }
    }

    /** Player state lives on the UI thread; D-Bus reads hop there (or run inline if already on it). */
    private fun <T> readUi(block: () -> T): T =
        if (SwingUtilities.isEventDispatchThread()) block() else runBlocking(Dispatchers.Main) { block() }

    // ---- org.mpris.MediaPlayer2
    override fun Raise() = onUi(onRaise)
    override fun Quit() = onUi(onQuit)

    // ---- org.mpris.MediaPlayer2.Player
    override fun Next() = onUi { player.next() }
    override fun Previous() = onUi { player.previous() }
    override fun Pause() = onUi { player.pause() }
    override fun PlayPause() = onUi { player.togglePlay() }
    override fun Stop() = onUi { player.stop() }
    override fun Play() = onUi { player.play() }
    override fun Seek(offset: Long) = onUi { player.seekBy(offset / 1000) }
    override fun SetPosition(trackId: DBusPath, position: Long) = onUi {
        if (trackId.path == trackPath(player.state.value.current?.uid)) player.seekTo(position / 1000)
    }
    override fun OpenUri(uri: String) = Unit

    override fun getObjectPath(): String = PATH

    // ---- org.freedesktop.DBus.Properties

    private fun trackPath(uid: Long?) = if (uid == null) "/org/mpris/MediaPlayer2/TrackList/NoTrack" else "/app/musikbox/track/$uid"

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("musikbox"),
        "DesktopEntry" to Variant("musikbox"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    private fun playerProps(): Map<String, Variant<*>> = readUi {
        val s = player.state.value
        val song = s.song
        val metadata = buildMap<String, Variant<*>> {
            put("mpris:trackid", Variant(DBusPath(trackPath(s.current?.uid))))
            if (song != null) {
                put("mpris:length", Variant(song.durationSec * 1_000_000L))
                put("xesam:title", Variant(song.title))
                song.artist?.let { put("xesam:artist", Variant(listOf(it), "as")) }
                song.album?.let { put("xesam:album", Variant(it)) }
                song.track?.let { put("xesam:trackNumber", Variant(it)) }
                library.coverUrl(song.coverArt, 500)?.let { put("mpris:artUrl", Variant(it)) }
            }
        }
        mapOf(
            "PlaybackStatus" to Variant(
                when {
                    s.isPlaying -> "Playing"
                    s.current != null -> "Paused"
                    else -> "Stopped"
                },
            ),
            "LoopStatus" to Variant(
                when (s.repeat) {
                    Repeat.Off -> "None"
                    Repeat.All -> "Playlist"
                    Repeat.One -> "Track"
                },
            ),
            "Rate" to Variant(1.0),
            "Shuffle" to Variant(s.shuffle),
            "Metadata" to Variant(metadata, "a{sv}"),
            "Volume" to Variant(s.volume / 100.0),
            "Position" to Variant(player.position.value * 1000L),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "CanGoNext" to Variant(s.hasNext),
            "CanGoPrevious" to Variant(s.hasPrevious),
            "CanPlay" to Variant(s.current != null),
            "CanPause" to Variant(s.current != null),
            "CanSeek" to Variant(s.current != null),
            "CanControl" to Variant(true),
        )
    }

    private fun props(iface: String) = when (iface) {
        IFACE_ROOT -> rootProps()
        IFACE_PLAYER -> playerProps()
        else -> throw DBusExecutionException("Unknown interface $iface")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (props(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property $propertyName")) as A

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props(interfaceName)

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        val v = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "Shuffle" -> onUi { player.setShuffle(v as Boolean) }
            "LoopStatus" -> onUi {
                player.setRepeat(
                    when (v as String) {
                        "Track" -> Repeat.One
                        "Playlist" -> Repeat.All
                        else -> Repeat.Off
                    },
                )
            }
            "Volume" -> onUi { player.setVolume(((v as Number).toDouble() * 100).toInt()) }
            else -> throw DBusExecutionException("Property $propertyName is read-only")
        }
    }
}
