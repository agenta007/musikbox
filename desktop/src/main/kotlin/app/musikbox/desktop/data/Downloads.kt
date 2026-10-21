package app.musikbox.desktop.data

import app.musikbox.data.remote.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private fun fileKey(id: String): String = URLEncoder.encode(id, Charsets.UTF_8)
private fun idFromKey(key: String): String = URLDecoder.decode(key, Charsets.UTF_8)

/** Streams [url] to [target] via a .part file so a partial download is never mistaken for a full one. */
private fun fetchTo(http: OkHttpClient, url: String, target: File) {
    val part = File(target.parentFile, target.name + ".part")
    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        val type = resp.header("Content-Type").orEmpty()
        if (type.contains("json") || type.contains("xml")) throw IOException("Server returned an error instead of audio")
        part.outputStream().use { out -> resp.body!!.byteStream().use { it.copyTo(out) } }
    }
    if (!part.renameTo(target)) throw IOException("Couldn't move ${part.name}")
}

/** Songs pinned for offline playback, stored as original files under ~/.local/share/musikbox/downloads. */
class Downloads(
    private val dir: File,
    private val api: SubsonicClient,
    private val http: OkHttpClient,
    scope: CoroutineScope,
) {
    private val _downloaded = MutableStateFlow(scan())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val queue = Channel<Song>(Channel.UNLIMITED)

    init {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        repeat(3) {
            scope.launch(Dispatchers.IO) { for (song in queue) download(song) }
        }
    }

    private fun scan(): Set<String> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.map { idFromKey(it.name) }?.toSet().orEmpty()

    private fun fileFor(id: String) = File(dir, fileKey(id))

    fun file(id: String): File? = if (id in _downloaded.value) fileFor(id).takeIf { it.exists() } else null

    fun ensure(songs: List<Song>) {
        val skip = _downloaded.value + _pending.value
        songs.filter { it.id !in skip }.forEach { song ->
            _pending.update { it + song.id }
            queue.trySend(song)
        }
    }

    private fun download(song: Song) {
        if (song.id !in _pending.value) return // removed while queued
        val result = runCatching {
            val url = api.streamUrl(song.id, 0) ?: throw IOException("Not signed in")
            fetchTo(http, url, fileFor(song.id))
        }
        if (song.id !in _pending.value) { fileFor(song.id).delete(); return } // removed mid-download
        _pending.update { it - song.id }
        if (result.isSuccess) _downloaded.update { it + song.id }
    }

    fun remove(ids: Collection<String>) {
        val set = ids.toSet()
        _pending.update { it - set }
        set.forEach { fileFor(it).delete() }
        _downloaded.update { it - set }
    }

    fun removeAll() {
        _pending.value = emptySet()
        dir.listFiles()?.forEach { it.delete() }
        _downloaded.value = emptySet()
    }

    fun bytesUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/**
 * LRU cache of streamed songs (~/.cache/musikbox/audio). While a song streams through mpv,
 * a copy is fetched here in the background so replays and offline sessions hit disk.
 */
class StreamCache(
    private val dir: File,
    private val maxBytes: () -> Long,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun fileFor(id: String, bitrate: Int) = File(dir, fileKey(id) + "@" + bitrate)

    fun file(id: String, bitrate: Int): File? =
        fileFor(id, bitrate).takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun fetch(id: String, bitrate: Int, url: String) {
        val target = fileFor(id, bitrate)
        if (target.exists() || !inFlight.add(target.name)) return
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { fetchTo(http, url, target) }
                evict()
            } finally {
                inFlight.remove(target.name)
            }
        }
    }

    @Synchronized
    private fun evict() {
        val files = dir.listFiles { f -> !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val limit = maxBytes()
        for (f in files) {
            if (total <= limit) break
            total -= f.length()
            f.delete()
        }
    }

    fun bytesUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
