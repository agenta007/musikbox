package app.musikbox.desktop.sync

import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.data.Library
import app.musikbox.desktop.data.SettingsRepo
import app.musikbox.desktop.data.Song
import app.musikbox.desktop.player.Player
import app.musikbox.sync.NowPlayingInfo
import app.musikbox.sync.PrefRecord
import app.musikbox.sync.RemoteSong
import app.musikbox.sync.SyncCrypto.hexToBytes
import app.musikbox.sync.SyncEngine
import app.musikbox.sync.SyncHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.musikbox.sync.SyncCrypto.toHex

/** Desktop side of device sync: maps the library / player onto the shared [SyncHost] contract. */
class DesktopSyncHost(
    private val settings: SettingsRepo,
    private val library: Library,
    private val player: Player,
) : SyncHost {
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        if (settings.current.deviceId == null) settings.update { it.copy(deviceId = SyncEngine.newDeviceId()) }
    }

    override val deviceId: String get() = settings.current.deviceId!!
    override val deviceName: String
        get() = settings.current.deviceName?.takeIf { it.isNotBlank() } ?: defaultName
    override val platform = "linux"
    override val appVersion = AppPaths.version

    override fun syncKey(): ByteArray? =
        settings.current.syncKey?.takeIf { settings.current.syncEnabled && settings.current.credentials != null }?.hexToBytes()

    override fun adoptSyncKey(key: ByteArray) = settings.update { it.copy(syncKey = key.toHex()) }

    override fun shareDownloadList() = settings.current.shareDownloadList
    override fun manualPeers() = settings.current.manualPeers

    override suspend fun localPrefs(): Map<String, PrefRecord> = withContext(Dispatchers.Main) { library.syncRecords() }

    override suspend fun applyPrefs(changes: Map<String, PrefRecord>) = withContext(Dispatchers.Main) {
        library.applySyncedPrefs(changes)
    }

    override suspend fun nowPlaying(): NowPlayingInfo? = withContext(Dispatchers.Main) {
        val s = player.state.value
        if (s.current == null || s.queue.isEmpty()) return@withContext null
        NowPlayingInfo(
            playing = s.isPlaying,
            positionMs = player.position.value,
            index = s.pos.coerceAtLeast(0),
            queue = s.queue.map { it.song.toRemote() },
            playlistId = s.sourcePlaylistId,
        )
    }

    override suspend fun pausePlayback() = withContext(Dispatchers.Main) { player.pause() }

    override fun log(message: String) {
        System.err.println("[musikbox sync] $message")
        _log.update { (it + "${stamp.format(Date())}  $message").takeLast(200) }
    }

    companion object {
        val defaultName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: System.getenv("HOSTNAME") ?: "Linux desktop"
    }
}

fun Song.toRemote() = RemoteSong(id, title, artist, album, albumId, artistId, coverArt, durationSec)

fun RemoteSong.toSong() = Song(
    id = id, title = title, artist = artist, album = album, albumId = albumId,
    artistId = artistId, coverArt = coverArt, durationSec = durationSec,
)
