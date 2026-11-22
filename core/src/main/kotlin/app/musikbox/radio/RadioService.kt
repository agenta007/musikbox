package app.musikbox.radio

import app.musikbox.data.remote.SongDto
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class RadioSource { Spotify, Server }

/** A song's radio: the songs to play after it (never the seed itself), and where they came from. */
data class Radio(
    val name: String,
    val songs: List<SongDto>,
    val source: RadioSource,
    /** Spotify only: how many songs Spotify's radio had, of which [spotifyMatched] are in the library. */
    val spotifyTotal: Int = 0,
    val spotifyMatched: Int = 0,
    /** Too few of Spotify's songs are here, so the server's similar songs follow them. */
    val toppedUp: Boolean = false,
    /** Scout is downloading the radio's other songs into the library. */
    val downloading: Boolean = false,
    /** Why Spotify wasn't used although scout is set up, for the message. */
    val spotifyProblem: String? = null,
) {
    /** One line for a snackbar. */
    val message: String
        get() = when {
            source == RadioSource.Spotify && downloading ->
                "$name: $spotifyMatched of $spotifyTotal songs here, scout is getting the rest"
            source == RadioSource.Spotify && toppedUp ->
                "$name: $spotifyMatched of $spotifyTotal songs are in your library, then similar songs from your server"
            source == RadioSource.Spotify -> "$name: $spotifyMatched of $spotifyTotal songs are in your library"
            spotifyProblem != null -> "$name from your server. Spotify: $spotifyProblem"
            else -> "$name: similar songs from your server"
        }
}

@Serializable
internal data class ScoutRadio(
    val name: String = "",
    val songs: List<SongDto> = emptyList(),
    val total: Int = 0,
    val downloading: Boolean = false,
    val error: String? = null,
)

/**
 * Song radio. With a scout address set, Spotify's radio for the song, through scout on the
 * server (it holds the Spotify login and matches the songs to the library). Topped up, or
 * replaced when scout can't help, by the server's own similar songs (Navidrome's
 * `getSimilarSongs2`, from Last.fm / ListenBrainz / Deezer).
 */
class RadioService(
    http: OkHttpClient,
    private val api: SubsonicClient,
    private val credentials: () -> Credentials?,
    private val scoutUrl: () -> String?,
) {
    // Scout may have to start a browser and sign in to Spotify first: allow it time.
    private val http = http.newBuilder().readTimeout(150, TimeUnit.SECONDS).callTimeout(160, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    suspend fun radio(seedId: String, artist: String?, title: String, durationSec: Int): Result<Radio> = runCatching {
        val scout = scoutUrl()?.trim()?.takeIf { it.isNotEmpty() }
        var problem: String? = null
        if (scout != null && !artist.isNullOrBlank()) {
            try {
                val found = askScout(scout, artist, title, durationSec)
                val songs = found.songs.filter { it.id != seedId }
                if (songs.isNotEmpty()) {
                    val topped = if (songs.size >= MIN_SONGS) songs else merge(songs, similar(seedId).filter { it.id != seedId })
                    return@runCatching Radio(
                        name = found.name.ifBlank { "$title Radio" },
                        songs = topped.take(MAX_SONGS),
                        source = RadioSource.Spotify,
                        spotifyTotal = found.total,
                        spotifyMatched = songs.size,
                        toppedUp = topped.size > songs.size,
                        downloading = found.downloading,
                    )
                }
                problem = "none of its songs are in your library"
            } catch (e: IOException) {
                problem = e.message ?: "scout didn't answer"
            }
        }
        val songs = similar(seedId).filter { it.id != seedId }
        if (songs.isEmpty()) throw IOException(problem?.let { "No radio for $title. Spotify: $it" } ?: "Your server knows no songs similar to $title")
        Radio("$title Radio", songs.take(MAX_SONGS), RadioSource.Server, spotifyProblem = problem)
    }

    private suspend fun similar(seedId: String): List<SongDto> =
        runCatching { api.getSimilarSongs2(seedId, MAX_SONGS) }.getOrDefault(emptyList())

    private suspend fun askScout(base: String, artist: String, title: String, durationSec: Int): ScoutRadio = withContext(Dispatchers.IO) {
        val creds = credentials() ?: throw IOException("Not signed in")
        val url = scoutRadioUrl(base) ?: throw IOException("the scout address isn't valid")
        val body = buildJsonObject {
            put("u", creds.username); put("t", creds.token); put("s", creds.salt)
            put("artist", artist); put("title", title)
            if (durationSec > 0) put("seconds", durationSec)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).header("X-Scout", "1").build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = try {
                json.decodeFromString<ScoutRadio>(text)
            } catch (e: SerializationException) {
                throw IOException(if (resp.isSuccessful) "scout's answer didn't make sense" else "scout answered HTTP ${resp.code}")
            } catch (e: IllegalArgumentException) {
                throw IOException("scout answered HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) throw IOException(parsed.error ?: "scout answered HTTP ${resp.code}")
            parsed
        }
    }

    companion object {
        /** Fewer Spotify songs in the library than this, and the server's similar songs fill in. */
        const val MIN_SONGS = 10
        const val MAX_SONGS = 60

        /** "scout.example.com", "https://x/scout/" -> "https://x/scout/api/radio". Plain http for LAN addresses. */
        fun scoutRadioUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = (if ("://" in trimmed) trimmed else "http://$trimmed").trimEnd('/')
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return url.newBuilder().addPathSegments("api/radio").build().toString()
        }

        /** Spotify's songs first, then similar songs not already there. */
        fun merge(first: List<SongDto>, more: List<SongDto>): List<SongDto> {
            val seen = first.mapTo(HashSet()) { it.id }
            return first + more.filter { seen.add(it.id) }
        }
    }
}
