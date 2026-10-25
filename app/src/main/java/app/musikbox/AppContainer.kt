package app.musikbox

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.musikbox.data.ActivityLog
import app.musikbox.discover.DiscoverEngine
import app.musikbox.discover.DiscoverModel
import app.musikbox.lyrics.FileLyricsCache
import app.musikbox.lyrics.LyricsService
import app.musikbox.sync.AndroidSyncHost
import app.musikbox.sync.SyncEngine
import kotlinx.coroutines.flow.drop
import app.musikbox.data.LibraryRepository
import app.musikbox.data.local.MusikboxDatabase
import app.musikbox.data.local.lyricsQuery
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.SettingsStore
import app.musikbox.playback.MediaResolver
import app.musikbox.playback.OfflineManager
import app.musikbox.playback.PlayerConnection
import app.musikbox.playback.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import app.musikbox.sync.CloudSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import app.musikbox.playback.VisualizerTap

/** Hand-rolled DI: one instance per process, created in [MusikboxApp.onCreate] on the main thread. */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(appContext, scope)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api = SubsonicClient(okHttp) { settings.current.credentials }

    val database = MusikboxDatabase.build(appContext)

    private val databaseProvider = StandaloneDatabaseProvider(appContext)

    /** LRU cache of everything streamed. Size is read at startup; changes apply after restart. */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(settings.current.cacheSizeMb * 1024L * 1024L),
            databaseProvider,
        )
    }

    private val downloadCache = SimpleCache(File(appContext.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)

    private val httpDataSourceFactory = OkHttpDataSource.Factory(okHttp).setUserAgent("musikbox/${BuildConfig.VERSION_NAME}")

    val log = ActivityLog(appContext, scope)

    val offline = OfflineManager(
        appContext, databaseProvider, downloadCache, httpDataSourceFactory, api, scope, log,
        initiallyPaused = settings.current.downloadsPaused,
    )

    val library = LibraryRepository(database, api, settings, offline, scope, log)

    val resolver = MediaResolver(api, settings, offline)

    val queueStore = QueueStore(appContext)

    /** PCM tap for the Now Playing visualizer (same process as the playback service). */
    val visualizerTap = VisualizerTap()

    val player = PlayerConnection(appContext, library, queueStore, scope)

    val discover = DiscoverModel(DiscoverEngine(api), scope)

    val lyrics = LyricsService(
        api, okHttp, FileLyricsCache(File(appContext.filesDir, "lyrics")),
        lrclibUrl = { settings.current.takeIf { it.lyricsOnline }?.lyricsServer },
        userAgent = "musikbox/${BuildConfig.VERSION_NAME} (https://src.apostolat.org/neo/musikbox)",
    )

    val syncHost = AndroidSyncHost(appContext, settings, library, player, log)
    val sync = SyncEngine(syncHost, okHttp, scope)
    /** Saved playlists / recents / download list through the server's private `.musikbox` playlist. */
    val cloud = CloudSync(api, syncHost, scope, { settings.current.credentials?.username }, onKeyChanged = { sync.syncNow() })
    private val multicastLock = syncHost.multicastLock()
    private var likedLoop: Job? = null

    fun startSync() {
        runCatching { multicastLock?.acquire() }
        sync.start()
        cloud.start()
        // Stars live on the server; refresh the liked list on the same cadence as server sync.
        likedLoop?.cancel()
        likedLoop = scope.launch {
            while (true) {
                delay(2 * 60_000L)
                if (settings.current.credentials != null) library.syncLiked()
            }
        }
    }

    /** App came to the foreground: catch up with changes made on other devices. */
    fun onForeground() {
        if (settings.current.credentials == null || !settings.current.syncEnabled) return
        cloud.requestSync(300)
        sync.requestSync(300)
        scope.launch { library.syncLiked() }
    }

    fun stopSync() {
        likedLoop?.cancel()
        cloud.stop()
        sync.stop()
        runCatching { multicastLock?.release() }
    }

    init {
        scope.launch {
            settings.settings.map { it.wifiOnlyDownloads }.distinctUntilChanged().collect(offline::setWifiOnly)
        }
        if (settings.current.syncEnabled) startSync()
        offline.onSongsQueued = { songs -> scope.launch { songs.forEach { lyrics.prefetch(it.lyricsQuery()) } } }
        // Any local favourite / play / download-list change is pushed to the other devices.
        scope.launch { library.prefsChanges.drop(1).collect { sync.requestSync(); cloud.requestSync() } }
        // Auto-restart on every launch: re-queue anything in the download list that is missing or failed.
        scope.launch {
            offline.awaitReady()
            if (settings.current.credentials != null && !settings.current.downloadsPaused) {
                library.refreshOfflineDownloads()
            }
        }
    }

    /** Download cache (read-only) → LRU stream cache → network. */
    fun playbackDataSourceFactory(): DataSource.Factory {
        val streaming = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null)
    }

    suspend fun streamCacheBytes(): Long = withContext(Dispatchers.IO) { streamCache.cacheSpace }

    suspend fun clearStreamCache() = withContext(Dispatchers.IO) {
        streamCache.keys.toList().forEach { runCatching { streamCache.removeResource(it) } }
    }

    /** Sign out: forget credentials and every cached byte that belongs to this server. */
    suspend fun signOut() {
        player.clearQueue()
        library.removeAllDownloads()
        clearStreamCache()
        withContext(Dispatchers.IO) { database.clearAllTables() }
        discover.clear()
        settings.clearCredentials()
    }
}
