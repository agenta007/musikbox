package app.musikbox.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.musikbox.data.ActivityLog
import app.musikbox.data.local.SongEntity
import app.musikbox.data.remote.SubsonicClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

enum class SongDlState { Queued, Downloading, Completed, Failed, Stopped }

data class SongDownload(
    val songId: String,
    val title: String,
    val state: SongDlState,
    /** 0–100, or -1 when unknown. */
    val percent: Float = -1f,
    val bytes: Long = 0L,
    val error: String? = null,
    /** Automatic retries spent since the last success or manual retry. */
    val attempts: Int = 0,
)

data class DownloadStatus(
    val ready: Boolean = false,
    val paused: Boolean = false,
    /** Non-null while downloads wait on connectivity: "Wi-Fi" or "a network". */
    val waitingFor: String? = null,
    val bytesPerSecond: Long = 0L,
)

/**
 * Pins whole tracks to disk via Media3's [DownloadManager], in a never-evicted cache that
 * playback reads before the streaming cache.
 *
 * Self-healing: failed songs are retried with exponential back-off, everything failed is
 * re-queued when the network comes back, and [DownloadWorker] continues the work in the
 * background. Every notable event goes to the [ActivityLog].
 *
 * The [DownloadManager] must only be touched on the main thread.
 */
@OptIn(UnstableApi::class)
class OfflineManager(
    private val context: Context,
    databaseProvider: DatabaseProvider,
    val cache: Cache,
    upstream: DataSource.Factory,
    private val api: SubsonicClient,
    private val scope: CoroutineScope,
    private val log: ActivityLog,
    initiallyPaused: Boolean,
) {
    val downloadManager = DownloadManager(
        context, databaseProvider, cache, upstream, Executors.newFixedThreadPool(3),
    ).apply {
        maxParallelDownloads = 3
        minRetryCount = 3 // ExoPlayer-level retries inside one attempt
    }

    private val ready = CompletableDeferred<Unit>()

    private val _songs = MutableStateFlow<Map<String, SongDownload>>(emptyMap())
    /** Every song the download manager knows about, by song id. */
    val songs: StateFlow<Map<String, SongDownload>> = _songs.asStateFlow()

    val downloaded: StateFlow<Set<String>> = idsIn(SongDlState.Completed)
    val pending: StateFlow<Set<String>> = idsIn(SongDlState.Queued, SongDlState.Downloading)
    val failed: StateFlow<Set<String>> = idsIn(SongDlState.Failed)

    private val _status = MutableStateFlow(DownloadStatus(paused = initiallyPaused))
    val status: StateFlow<DownloadStatus> = _status.asStateFlow()

    private val attempts = HashMap<String, Int>()
    private val retryJobs = HashMap<String, Job>()
    private var progressJob: Job? = null
    private var wifiOnly = true
    private var hadWork = false

    init {
        if (initiallyPaused) downloadManager.pauseDownloads()
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                reload()
                applyRequirements(downloadManager.notMetRequirements)
            }

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) =
                onChanged(download, finalException)

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val id = download.request.id
                _songs.update { it - id }
                attempts.remove(id)
                retryJobs.remove(id)?.cancel()
            }

            override fun onIdle(downloadManager: DownloadManager) {
                if (!hadWork) return
                hadWork = false
                val all = _songs.value.values
                val failedCount = all.count { it.state == SongDlState.Failed }
                val doneCount = all.count { it.state == SongDlState.Completed }
                if (failedCount > 0) log.w("Downloads idle — $doneCount on device, $failedCount failed")
                else log.i("All downloads finished — $doneCount songs on device")
            }

            override fun onRequirementsStateChanged(
                downloadManager: DownloadManager,
                requirements: Requirements,
                notMetRequirements: Int,
            ) = applyRequirements(notMetRequirements)

            override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) {
                _status.update { it.copy(paused = downloadsPaused) }
            }
        })
        watchNetwork()
    }

    private fun idsIn(vararg states: SongDlState): StateFlow<Set<String>> =
        _songs.map { m -> m.values.filter { it.state in states }.map { it.songId }.toSet() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun awaitReady() = ready.await()

    fun isDownloaded(songId: String) = _songs.value[songId]?.state == SongDlState.Completed

    // ---------------------------------------------------------------- state tracking

    private fun reload() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    buildMap {
                        downloadManager.downloadIndex.getDownloads().use { c ->
                            while (c.moveToNext()) entryFor(c.download)?.let { put(it.songId, it) }
                        }
                    }
                }.onFailure { log.e("Couldn't read the download index: ${it.message}") }.getOrDefault(emptyMap())
            }
            _songs.value = loaded
            _status.update { it.copy(ready = true) }
            ready.complete(Unit)
            val failedCount = loaded.values.count { it.state == SongDlState.Failed }
            val pendingCount = loaded.values.count { it.state == SongDlState.Queued || it.state == SongDlState.Downloading }
            log.i(
                "Download manager ready — ${loaded.values.count { it.state == SongDlState.Completed }} on device" +
                    (if (pendingCount > 0) ", $pendingCount resuming" else "") +
                    (if (failedCount > 0) ", $failedCount failed" else "") +
                    (if (_status.value.paused) " (paused)" else ""),
            )
            if (pendingCount > 0) { hadWork = true; ensureProgressPolling() }
        }
    }

    private fun entryFor(d: Download, error: String? = null): SongDownload? {
        val state = when (d.state) {
            Download.STATE_COMPLETED -> SongDlState.Completed
            Download.STATE_FAILED -> SongDlState.Failed
            Download.STATE_DOWNLOADING -> SongDlState.Downloading
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> SongDlState.Queued
            Download.STATE_STOPPED -> SongDlState.Stopped
            else -> return null // removing
        }
        val id = d.request.id
        return SongDownload(
            songId = id,
            title = titleOf(d.request),
            state = state,
            percent = d.percentDownloaded.takeIf { it != C.PERCENTAGE_UNSET.toFloat() } ?: -1f,
            bytes = d.bytesDownloaded,
            error = if (state == SongDlState.Failed) error ?: _songs.value[id]?.error ?: "Download failed" else null,
            attempts = attempts[id] ?: 0,
        )
    }

    private fun onChanged(d: Download, finalException: Exception?) {
        val id = d.request.id
        val previous = _songs.value[id]?.state
        val error = finalException?.let(::describe)
        val entry = entryFor(d, error)
        _songs.update { if (entry == null) it - id else it + (id to entry) }
        val title = titleOf(d.request)
        when (d.state) {
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> hadWork = true
            Download.STATE_DOWNLOADING -> if (previous != SongDlState.Downloading) {
                hadWork = true
                log.d("↓ $title")
                ensureProgressPolling()
            }
            Download.STATE_COMPLETED -> {
                if (attempts.remove(id) != null) log.i("✓ $title (recovered)") else log.d("✓ $title (${mb(d.bytesDownloaded)})")
            }
            Download.STATE_FAILED -> {
                log.w("✗ $title — ${error ?: "unknown error"}")
                scheduleRetry(id, title)
            }
        }
    }

    private fun applyRequirements(notMet: Int) {
        val waiting = when {
            notMet == 0 -> null
            notMet and Requirements.NETWORK_UNMETERED != 0 -> "Wi-Fi"
            notMet and Requirements.NETWORK != 0 -> "a network"
            else -> "device conditions"
        }
        val before = _status.value.waitingFor
        _status.update { it.copy(waitingFor = waiting) }
        if (waiting != before) {
            if (waiting != null) log.i("Downloads waiting for $waiting")
            else if (before != null) log.i("Connection OK — downloads running")
        }
    }

    private fun ensureProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            val lastBytes = HashMap<String, Long>()
            var speed = 0.0
            while (true) {
                val active = downloadManager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                if (active.isEmpty()) {
                    _status.update { it.copy(bytesPerSecond = 0) }
                    break
                }
                var delta = 0L
                active.forEach { d ->
                    val id = d.request.id
                    delta += (d.bytesDownloaded - (lastBytes[id] ?: d.bytesDownloaded)).coerceAtLeast(0)
                    lastBytes[id] = d.bytesDownloaded
                }
                speed = if (speed == 0.0) delta.toDouble() else speed * 0.6 + delta * 0.4
                _status.update { it.copy(bytesPerSecond = speed.toLong()) }
                _songs.update { m ->
                    m + active.mapNotNull { d -> entryFor(d)?.let { d.request.id to it } }
                }
                delay(1_000)
            }
        }
    }

    // ---------------------------------------------------------------- retry / restart

    private fun scheduleRetry(id: String, title: String) {
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n
        _songs.update { m -> m[id]?.let { m + (id to it.copy(attempts = n)) } ?: m }
        if (n > MAX_AUTO_RETRIES) {
            log.e("Gave up on $title after $MAX_AUTO_RETRIES retries — will try again when the network changes or on “Retry failed”")
            return
        }
        val delaySec = min(MAX_BACKOFF_SEC, BASE_BACKOFF_SEC shl (n - 1))
        log.i("Retrying $title in ${delaySec}s (retry $n of $MAX_AUTO_RETRIES)")
        retryJobs.remove(id)?.cancel()
        retryJobs[id] = scope.launch {
            delay(delaySec * 1000)
            retryJobs.remove(id)
            if (_songs.value[id]?.state == SongDlState.Failed) requeue(id)
        }
        DownloadWorker.enqueue(context, wifiOnly)
    }

    /** Re-queues every failed song. [reset] clears their back-off counters (manual retry / network change). */
    suspend fun retryFailed(reason: String, reset: Boolean = true): Int = withContext(Dispatchers.Main) {
        awaitReady()
        val ids = _songs.value.values
            .filter { it.state == SongDlState.Failed && (reset || retryJobs[it.songId]?.isActive != true) }
            .map { it.songId }
        if (ids.isEmpty()) return@withContext 0
        log.i("Retrying ${ids.size} failed songs — $reason")
        ids.forEach { id ->
            if (reset) attempts.remove(id)
            retryJobs.remove(id)?.cancel()
            requeue(id)
        }
        startService()
        ids.size
    }

    private fun requeue(id: String) {
        val title = _songs.value[id]?.title ?: id
        // Rebuild the request: the URL embeds auth, which may have changed since it was queued.
        request(id, title)?.let(downloadManager::addDownload)
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        awaitReady()
                        if (failed.value.isNotEmpty() && !_status.value.paused) retryFailed("network available")
                    }
                }
            })
        }.onFailure { log.w("Couldn't watch connectivity: ${it.message}") }
    }

    // ---------------------------------------------------------------- commands

    private fun request(id: String, title: String): DownloadRequest? {
        val url = api.streamUrl(id, 0) ?: return null
        return DownloadRequest.Builder(id, Uri.parse(url))
            .setCustomCacheKey(cacheKeyFor(id, 0))
            .setData(title.toByteArray())
            .build()
    }

    /** Queues songs that aren't on disk or queued yet, and re-queues failed ones. */
    suspend fun ensureDownloaded(songs: List<SongEntity>, label: String? = null) = withContext(Dispatchers.Main) {
        awaitReady()
        val known = _songs.value
        val fresh = songs.distinctBy { it.id }.filter { known[it.id] == null || known[it.id]?.state == SongDlState.Stopped }
        // Failed songs with a back-off retry already scheduled are left to it.
        val failedOnes = songs.distinctBy { it.id }
            .filter { known[it.id]?.state == SongDlState.Failed && retryJobs[it.id]?.isActive != true }
        if (fresh.isEmpty() && failedOnes.isEmpty()) return@withContext
        fresh.forEach { s -> request(s.id, label(s))?.let(downloadManager::addDownload) }
        failedOnes.forEach { s -> requeue(s.id) }
        log.i(
            buildString {
                append("Queued ${fresh.size} songs")
                label?.let { append(" from $it") }
                if (failedOnes.isNotEmpty()) append(", retrying ${failedOnes.size} failed")
                if (_status.value.paused) append(" (paused)")
            },
        )
        startService()
        DownloadWorker.enqueue(context, wifiOnly)
    }

    /** Deletes and downloads again in one go (Media3 restarts a download that is being removed). */
    suspend fun redownload(songs: List<SongEntity>, label: String) = withContext(Dispatchers.Main) {
        awaitReady()
        val unique = songs.distinctBy { it.id }
        unique.forEach { s ->
            attempts.remove(s.id)
            retryJobs.remove(s.id)?.cancel()
            downloadManager.removeDownload(s.id)
            request(s.id, label(s))?.let(downloadManager::addDownload)
        }
        log.i("Re-downloading ${unique.size} songs — $label")
        startService()
        DownloadWorker.enqueue(context, wifiOnly)
    }

    suspend fun remove(songIds: Collection<String>, label: String? = null) = withContext(Dispatchers.Main) {
        awaitReady()
        songIds.forEach { downloadManager.removeDownload(it) }
        if (songIds.isNotEmpty()) log.i("Deleted ${songIds.size} downloaded songs" + (label?.let { " — $it" } ?: ""))
    }

    suspend fun removeAll(label: String) = withContext(Dispatchers.Main) {
        awaitReady()
        val n = _songs.value.size
        downloadManager.removeAllDownloads()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        attempts.clear()
        log.i("Deleted all $n downloads — $label")
    }

    fun setPaused(paused: Boolean) {
        if (paused) downloadManager.pauseDownloads() else downloadManager.resumeDownloads()
        _status.update { it.copy(paused = paused) }
        log.i(if (paused) "Downloads paused" else "Downloads resumed")
        if (!paused) {
            startService()
            DownloadWorker.enqueue(context, wifiOnly)
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        this.wifiOnly = wifiOnly
        downloadManager.requirements =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }

    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    /** Keeps downloads alive in a foreground service; may be refused while backgrounded. */
    private fun startService() {
        try {
            DownloadService.start(context, MusikDownloadService::class.java)
        } catch (e: IllegalStateException) {
            log.d("Foreground download service not allowed right now; continuing in-process")
        }
    }

    private fun label(s: SongEntity) = if (s.artist.isNullOrBlank()) s.title else "${s.title} — ${s.artist}"

    private fun titleOf(request: DownloadRequest) =
        request.data.takeIf { it.isNotEmpty() }?.let { String(it) } ?: request.id

    companion object {
        const val MAX_AUTO_RETRIES = 8
        private const val BASE_BACKOFF_SEC = 15L
        private const val MAX_BACKOFF_SEC = 15 * 60L

        fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

        /** Human-readable cause for a failed download, walking the exception chain. */
        fun describe(e: Throwable): String {
            var t: Throwable? = e
            var root: Throwable = e
            while (t != null) {
                when (t) {
                    is HttpDataSource.InvalidResponseCodeException -> return when (t.responseCode) {
                        401, 403 -> "HTTP ${t.responseCode} — not authorised (sign in again?)"
                        404 -> "HTTP 404 — song no longer on the server"
                        else -> "HTTP ${t.responseCode} from server"
                    }
                    is UnknownHostException -> return "Server not reachable (DNS)"
                    is ConnectException -> return "Can't connect to the server"
                    is SocketTimeoutException -> return "Timed out"
                }
                val msg = t.message.orEmpty()
                when {
                    "ENOSPC" in msg || "No space left" in msg -> return "Storage full"
                    "unexpected end of stream" in msg -> return "Server closed the connection (down or restarting?)"
                    "Failed to connect" in msg -> return "Can't connect to the server"
                }
                root = t
                t = t.cause
            }
            // Deepest cause, without the URL (it contains the auth token).
            val msg = root.message?.substringBefore("http")?.removeSuffix(" on ")?.trim()
            return if (msg.isNullOrBlank()) root.javaClass.simpleName else msg
        }
    }
}

/** One cache key per (song, quality) so transcoded and original bytes never mix. */
fun cacheKeyFor(songId: String, bitrate: Int) = if (bitrate <= 0) "song:$songId:raw" else "song:$songId:$bitrate"
