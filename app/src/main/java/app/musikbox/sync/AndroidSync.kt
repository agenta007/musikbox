package app.musikbox.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import app.musikbox.BuildConfig
import app.musikbox.data.ActivityLog
import app.musikbox.data.LibraryRepository
import app.musikbox.data.local.SongEntity
import app.musikbox.data.settings.SettingsStore
import app.musikbox.playback.PlayerConnection
import app.musikbox.sync.SyncCrypto.hexToBytes
import app.musikbox.sync.SyncCrypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Android side of device sync: maps library / player / settings onto the shared [SyncHost]. */
class AndroidSyncHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val library: LibraryRepository,
    private val player: PlayerConnection,
    private val log: ActivityLog,
) : SyncHost {

    /**
     * Fixed for the process lifetime. (Reading it back from DataStore right after the first
     * write raced: the flow hadn't caught up, so our own beacons looked like another device's.)
     */
    override val deviceId: String = settings.current.deviceId
        ?: SyncEngine.newDeviceId().also { id -> runBlocking { settings.setDeviceId(id) } }
    override val deviceName: String
        get() = settings.current.deviceName?.takeIf { it.isNotBlank() } ?: defaultName
    override val platform = "android"
    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun syncKey(): ByteArray? = settings.current.let { s ->
        s.syncKey?.takeIf { s.syncEnabled && s.credentials != null }?.hexToBytes()
    }

    override fun adoptSyncKey(key: ByteArray) = runBlocking { settings.setSyncKey(key.toHex()); Unit }

    override fun shareDownloadList() = settings.current.shareDownloadList
    override fun manualPeers() = settings.current.manualPeers

    override suspend fun localPrefs(): Map<String, PrefRecord> = library.syncRecords()
    override suspend fun applyPrefs(changes: Map<String, PrefRecord>) = library.applySyncedPrefs(changes)

    override suspend fun nowPlaying(): NowPlayingInfo? = withContext(Dispatchers.Main) {
        val (songs, index, position) = player.snapshot() ?: return@withContext null
        val state = player.state.value
        NowPlayingInfo(
            playing = state.isPlaying,
            positionMs = position,
            index = index,
            queue = songs.map { it.toRemote() },
            playlistId = state.sourcePlaylistId,
        )
    }

    override suspend fun pausePlayback() = withContext(Dispatchers.Main) { player.pause() }

    override fun log(message: String) = log.i("Sync: $message")

    /** Wi-Fi drivers drop broadcast/multicast frames unless an app holds this lock. */
    fun multicastLock(): WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("musikbox-sync")?.apply { setReferenceCounted(false) }

    companion object {
        val defaultName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .distinct().joinToString(" ").trim().ifEmpty { "Android" }
    }
}

fun SongEntity.toRemote() = RemoteSong(id, title, artist, album, albumId, artistId, coverArt, durationSec)

fun RemoteSong.toEntity() = SongEntity(
    id = id, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId,
    coverArt = coverArt, track = null, discNumber = null, year = null, genre = null,
    durationSec = durationSec, bitRate = null, suffix = null, sizeBytes = null, starred = false,
)
