package app.musikbox.desktop

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.desktop.data.Downloads
import app.musikbox.desktop.sync.DesktopSyncHost
import app.musikbox.discover.DiscoverEngine
import app.musikbox.discover.DiscoverModel
import app.musikbox.sync.SyncEngine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import app.musikbox.desktop.data.Library
import app.musikbox.desktop.data.SettingsRepo
import app.musikbox.desktop.data.StreamCache
import app.musikbox.desktop.player.Mpris
import app.musikbox.desktop.player.Mpv
import app.musikbox.desktop.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import app.musikbox.sync.CloudSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import app.musikbox.desktop.player.TrackPcm
import app.musikbox.desktop.player.ScreenLock
import app.musikbox.desktop.data.lyricsQuery
import app.musikbox.lyrics.FileLyricsCache
import app.musikbox.lyrics.LyricsService

/** Hand-rolled DI for the desktop app; mirrors the Android AppContainer. */
class DesktopContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsRepo()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api = SubsonicClient(okHttp) { settings.current.credentials }
    val downloads = Downloads(AppPaths.downloadsDir, api, okHttp, scope)
    val streamCache = StreamCache(AppPaths.audioCacheDir, { settings.current.cacheSizeMb * 1024L * 1024L }, okHttp, scope)
    val library = Library(api, settings, downloads, scope)
    val mpv = Mpv()
    val player = Player(mpv, api, settings, library, downloads, streamCache, scope)
    val trackPcm = TrackPcm(api, downloads, streamCache, settings, scope)
    var mpris: Mpris? = null
        private set
    val screenLock = ScreenLock()

    val discover = DiscoverModel(DiscoverEngine(api), scope)
    val lyrics = LyricsService(
        api, okHttp, FileLyricsCache(AppPaths.lyricsDir),
        lrclibUrl = { settings.current.takeIf { it.lyricsOnline }?.lyricsServer },
        userAgent = "musikbox/${AppPaths.version} (https://src.apostolat.org/neo/musikbox)",
    )
    val syncHost = DesktopSyncHost(settings, library, player)
    val sync = SyncEngine(syncHost, okHttp, scope)
    /** Saved playlists / recents / download list through the server's private `.musikbox` playlist. */
    val cloud = CloudSync(api, syncHost, scope, { settings.current.credentials?.username }, onKeyChanged = { sync.syncNow() })
    private var likedLoop: Job? = null

    fun startSync() {
        sync.start()
        cloud.start()
        likedLoop?.cancel()
        likedLoop = scope.launch {
            while (true) {
                delay(2 * 60_000L)
                if (settings.current.credentials != null) library.syncLiked()
            }
        }
    }

    fun stopSync() {
        likedLoop?.cancel()
        cloud.stop()
        sync.stop()
    }

    /** Window regained focus: catch up with changes made on other devices. */
    fun onForeground() {
        if (settings.current.credentials == null || !settings.current.syncEnabled) return
        cloud.requestSync(300)
        sync.requestSync(300)
        scope.launch { library.syncLiked() }
    }

    /** Call on the UI thread once the window exists. */
    fun start(onRaise: () -> Unit, onQuit: () -> Unit): Result<Unit> {
        val playerResult = player.start()
        mpris = Mpris(player, library, scope, onRaise, onQuit).also { it.start() }
        screenLock.start()
        downloads.onQueued = { songs -> scope.launch { songs.forEach { lyrics.prefetch(it.lyricsQuery()) } } }
        if (settings.current.syncEnabled) startSync()
        // Any local favourite / play / download-list change is pushed to the other devices.
        scope.launch { library.prefsFlow.drop(1).collect { sync.requestSync(); cloud.requestSync() } }
        return playerResult
    }

    fun shutdown() {
        stopSync()
        player.shutdown()
        mpris?.stop()
        screenLock.stop()
    }

    suspend fun signOut() {
        player.clear()
        library.removeAllDownloads()
        withContext(Dispatchers.IO) { streamCache.clear() }
        library.clear()
        discover.clear()
        settings.signOut()
    }
}
