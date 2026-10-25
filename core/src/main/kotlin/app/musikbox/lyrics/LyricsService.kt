package app.musikbox.lyrics

import app.musikbox.data.remote.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** What a lyrics lookup needs to know about a song. */
data class LyricsQuery(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int,
)

/**
 * Lyrics from the user's own server first (OpenSubsonic `getLyricsBySongId`: embedded tags and
 * sidecar .lrc/.ttml files; classic `getLyrics` on older servers). Only when the server has none,
 * and only if the user enabled it, an LRCLIB instance (lrclib.net or self-hosted) is asked.
 * Answers, including "none", are cached on disk so a song is looked up once.
 */
class LyricsService(
    private val api: SubsonicClient,
    private val http: OkHttpClient,
    private val cache: FileLyricsCache,
    /** LRCLIB base URL, or null while the online fallback is off. */
    private val lrclibUrl: () -> String?,
    private val userAgent: String,
) {
    suspend fun load(q: LyricsQuery, refresh: Boolean = false): LyricsResult {
        val online = lrclibUrl()
        val now = System.currentTimeMillis()
        if (!refresh) cache.get(q.id)?.let { c ->
            val fresh = if (c.lyrics != null) now - c.fetchedAt < FOUND_TTL_MS
            else now - c.fetchedAt < NONE_TTL_MS && (online == null || c.askedLrclib)
            if (fresh) return c.lyrics?.let { LyricsResult.Found(it) } ?: LyricsResult.None
        }

        val server = try {
            fromServer(q)
        } catch (e: IOException) {
            return cache.get(q.id)?.lyrics?.let { LyricsResult.Found(it) } ?: LyricsResult.Failed(e.message ?: "Server unreachable")
        }
        var found = server
        if (found == null && online != null) {
            found = try {
                Lrclib(http, online, userAgent).find(q)
            } catch (e: IOException) {
                return LyricsResult.Failed("LRCLIB: ${e.message ?: "unreachable"}")
            }
        }
        cache.put(q.id, CachedLyrics(found, now, askedLrclib = online != null))
        return found?.let { LyricsResult.Found(it) } ?: LyricsResult.None
    }

    suspend fun clearCache() = cache.clear()

    /** Warm the cache for offline use (downloads); failures are ignored. */
    suspend fun prefetch(q: LyricsQuery) {
        runCatching { load(q) }
    }

    private suspend fun fromServer(q: LyricsQuery): Lyrics? {
        val structured = try {
            api.getLyricsBySongId(q.id)
        } catch (e: IOException) {
            // Not an OpenSubsonic server (unknown method / HTTP 404): fall back to the classic call.
            val artist = q.artist ?: return null
            return api.getLyrics(artist, q.title)?.let { plainLyrics(it, LyricsSource.Server) }
        }
        return structured.toLyrics()
    }

    companion object {
        const val DEFAULT_LRCLIB = "https://lrclib.net"
        private const val FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        private const val NONE_TTL_MS = 24L * 3600 * 1000
    }
}

@Serializable
data class CachedLyrics(val lyrics: Lyrics?, val fetchedAt: Long, val askedLrclib: Boolean = false)

/** One small JSON file per song id. */
class FileLyricsCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    suspend fun get(id: String): CachedLyrics? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<CachedLyrics>(file(id).readText()) }.getOrNull()
    }

    suspend fun put(id: String, entry: CachedLyrics) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val f = file(id)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedLyrics.serializer(), entry))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() }; Unit }
}

/** Minimal LRCLIB client (https://lrclib.net/docs): exact match first, then a search by duration. */
private class Lrclib(private val http: OkHttpClient, baseUrl: String, private val userAgent: String) {
    private val base = (baseUrl.trim().trimEnd('/').takeIf { it.contains("://") } ?: "https://${baseUrl.trim().trimEnd('/')}")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class Track(
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    suspend fun find(q: LyricsQuery): Lyrics? {
        val artist = q.artist?.takeIf { it.isNotBlank() } ?: return null
        if (!q.album.isNullOrBlank() && q.durationSec > 0) {
            get("api/get", "track_name" to q.title, "artist_name" to artist, "album_name" to q.album, "duration" to q.durationSec.toString())
                ?.let { return json.decodeFromString<Track>(it).toLyrics() }
        }
        val hits = get("api/search", "track_name" to q.title, "artist_name" to artist)
            ?.let { json.decodeFromString<List<Track>>(it) }.orEmpty()
            .filter { q.durationSec <= 0 || it.duration == null || abs(it.duration - q.durationSec) <= 3 }
        val best = hits.firstOrNull { it.syncedLyrics != null } ?: hits.firstOrNull { it.plainLyrics != null || it.instrumental }
        return best?.toLyrics()
    }

    private fun Track.toLyrics(): Lyrics? = when {
        instrumental -> Lyrics(LyricsSource.Lrclib, synced = false, lines = emptyList(), instrumental = true)
        !syncedLyrics.isNullOrBlank() -> LrcParser.parse(syncedLyrics).takeIf { it.isNotEmpty() }?.let { Lyrics(LyricsSource.Lrclib, true, it) }
        !plainLyrics.isNullOrBlank() -> plainLyrics(plainLyrics, LyricsSource.Lrclib)
        else -> null
    }

    /** Body of a 200, null for a 404 (no match); anything else is an error. */
    private suspend fun get(path: String, vararg params: Pair<String, String>): String? = withContext(Dispatchers.IO) {
        val url = "$base/$path".toHttpUrlOrNull()?.newBuilder()?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
            ?: throw IOException("Invalid LRCLIB address: $base")
        http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("HTTP ${r.code}")
                else -> r.body?.string()
            }
        }
    }
}
