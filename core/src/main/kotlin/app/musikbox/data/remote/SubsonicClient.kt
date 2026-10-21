package app.musikbox.data.remote

import app.musikbox.data.settings.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class SubsonicException(val code: Int, message: String) : IOException(message)

/** Thin client for the Subsonic REST API (as implemented by Navidrome, Gonic, Airsonic…). */
class SubsonicClient(
    private val http: OkHttpClient,
    private val credentials: () -> Credentials?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private fun url(method: String, creds: Credentials, params: List<Pair<String, String>> = emptyList()): HttpUrl {
        val b = creds.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("rest")
            .addPathSegment("$method.view")
            .addQueryParameter("u", creds.username)
            .addQueryParameter("t", creds.token)
            .addQueryParameter("s", creds.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun call(
        method: String,
        params: List<Pair<String, String>> = emptyList(),
        creds: Credentials = credentials() ?: throw SubsonicException(10, "Not signed in"),
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(method, creds, params)).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            val envelope = try {
                json.decodeFromString<SubsonicEnvelope>(body)
            } catch (e: SerializationException) {
                throw IOException("This doesn't look like a Subsonic server", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("This doesn't look like a Subsonic server", e)
            }
            val r = envelope.response
            if (r.status != "ok") {
                throw SubsonicException(r.error?.code ?: 0, r.error?.message ?: "Server error")
            }
            r
        }
    }

    suspend fun ping(creds: Credentials): SubsonicResponse = call("ping", creds = creds)

    suspend fun getPlaylists(): List<PlaylistDto> = call("getPlaylists").playlists?.playlist.orEmpty()

    suspend fun getPlaylist(id: String): PlaylistDto =
        call("getPlaylist", listOf("id" to id)).playlist ?: throw IOException("Playlist $id missing")

    suspend fun search(query: String, count: Int = 60): List<SongDto> =
        call(
            "search3",
            listOf("query" to query, "songCount" to "$count", "albumCount" to "0", "artistCount" to "0"),
        ).searchResult3?.song.orEmpty().filterNot { it.isDir }

    suspend fun star(songId: String) { call("star", listOf("id" to songId)) }
    suspend fun unstar(songId: String) { call("unstar", listOf("id" to songId)) }

    suspend fun scrobble(songId: String, submission: Boolean) {
        call(
            "scrobble",
            listOf("id" to songId, "submission" to "$submission", "time" to "${System.currentTimeMillis()}"),
        )
    }

    suspend fun createPlaylist(name: String, songIds: List<String>) {
        call("createPlaylist", listOf("name" to name) + songIds.map { "songId" to it })
    }

    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        addSongIds: List<String> = emptyList(),
        removeIndexes: List<Int> = emptyList(),
        comment: String? = null,
        public: Boolean? = null,
    ) {
        val params = buildList {
            add("playlistId" to id)
            name?.let { add("name" to it) }
            comment?.let { add("comment" to it) }
            public?.let { add("public" to "$it") }
            addSongIds.forEach { add("songIdToAdd" to it) }
            removeIndexes.forEach { add("songIndexToRemove" to "$it") }
        }
        call("updatePlaylist", params)
    }

    suspend fun deletePlaylist(id: String) { call("deletePlaylist", listOf("id" to id)) }

    // ---- Discovery building blocks ----

    /** [type]: random, newest, frequent, recent, starred, highest, alphabeticalByName, byGenre… */
    suspend fun getAlbumList2(type: String, size: Int = 40, offset: Int = 0, genre: String? = null): List<AlbumDto> =
        call(
            "getAlbumList2",
            buildList {
                add("type" to type); add("size" to "$size"); add("offset" to "$offset")
                genre?.let { add("genre" to it) }
            },
        ).albumList2?.album.orEmpty()

    suspend fun getAlbum(id: String): AlbumDto =
        call("getAlbum", listOf("id" to id)).album ?: throw IOException("Album $id missing")

    suspend fun getArtist(id: String): ArtistDto =
        call("getArtist", listOf("id" to id)).artist ?: throw IOException("Artist $id missing")

    suspend fun getRandomSongs(size: Int = 50, genre: String? = null): List<SongDto> =
        call("getRandomSongs", buildList { add("size" to "$size"); genre?.let { add("genre" to it) } })
            .randomSongs?.song.orEmpty()

    /** Similar songs via the server's agents (Deezer / Last.fm / ListenBrainz / AudioMuse). May be empty. */
    suspend fun getSimilarSongs2(id: String, count: Int = 30): List<SongDto> =
        call("getSimilarSongs2", listOf("id" to id, "count" to "$count")).similarSongs2?.song.orEmpty()

    suspend fun getTopSongs(artistName: String, count: Int = 20): List<SongDto> =
        call("getTopSongs", listOf("artist" to artistName, "count" to "$count")).topSongs?.song.orEmpty()

    suspend fun getStarred2(): Starred2Dto = call("getStarred2").starred2 ?: Starred2Dto()

    /** [maxBitRate] 0 → original file, untouched. */
    fun streamUrl(songId: String, maxBitRate: Int): String? {
        val creds = credentials() ?: return null
        val params = if (maxBitRate > 0) {
            listOf("id" to songId, "maxBitRate" to "$maxBitRate", "format" to "mp3")
        } else {
            listOf("id" to songId, "format" to "raw")
        }
        return url("stream", creds, params).toString()
    }

    fun coverArtUrl(coverArtId: String?, size: Int = 600): String? {
        if (coverArtId.isNullOrBlank()) return null
        val creds = credentials() ?: return null
        return url("getCoverArt", creds, listOf("id" to coverArtId, "size" to "$size")).toString()
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "musikbox"

        /** Normalizes user input like "music.local:4533/" into "http(s)://music.local:4533". */
        fun normalizeServerUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }
    }
}
