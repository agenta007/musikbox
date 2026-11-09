package app.musikbox.discover

import app.musikbox.data.remote.GenreDto
import app.musikbox.data.remote.SongDto
import app.musikbox.data.remote.Starred2Dto
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.sync.SyncedMixGenres
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlin.math.ln
import kotlin.random.Random

const val MAX_MIX_GENRES = 5
const val DAILY_MIX_SIZE = 40

/** Below this, a genre is too small to give a mix worth having. */
private const val MIN_MIX_SONGS = 5
/** Songs played this recently sink down the mix, so tomorrow's doesn't repeat today's. */
private const val RESTED_DAYS = 3L
/** The share of each mix set aside for songs you've never played. */
private const val UNHEARD_SHARE = 0.3

@Serializable
data class DailyMix(val genre: String, val songs: List<SongDto>)

/**
 * One mix per genre, the mix most of the listening happens in: mostly songs you already like,
 * with some you haven't heard. Discover is the opposite, all unheard songs.
 */
suspend fun buildDailyMixes(
    api: SubsonicClient,
    picked: List<String>,
    seed: Long,
    size: Int = DAILY_MIX_SIZE,
): List<DailyMix> = coroutineScope {
    val frequent = async { orEmpty { api.getAlbumList2("frequent", 50) } }
    val recent = async { orEmpty { api.getAlbumList2("recent", 50) } }
    val starred = async { runCatching { api.getStarred2() }.getOrDefault(Starred2Dto()) }
    val available = async { api.getGenres() }
    val profile = buildProfile(frequent.await(), recent.await(), starred.await())
    val genres = picked.ifEmpty { defaultGenres(profile, available.await()) }
    val pools = genres.map { g -> async { g to orEmpty { api.getRandomSongs(500, genre = g) } } }.awaitAll()

    val rng = Random(seed)
    val used = HashSet<String>()
    pools.mapNotNull { (genre, pool) ->
        val songs = dailyMix(pool, profile, rng, size, exclude = used)
        if (songs.size < MIN_MIX_SONGS) return@mapNotNull null
        used += songs.map { it.id }
        DailyMix(genre, songs)
    }
}

private inline fun <T> orEmpty(block: () -> List<T>): List<T> = runCatching(block).getOrDefault(emptyList())

/** Pure: the genres to start from before any are picked. Your top genres, then the server's biggest. */
fun defaultGenres(profile: TasteProfile, available: List<GenreDto>, count: Int = MAX_MIX_GENRES): List<String> {
    val usable = available.filter { it.value.isNotBlank() && it.songCount >= MIN_MIX_SONGS }
    val byName = usable.associateBy { it.value.lowercase() }
    val liked = profile.topGenres.mapNotNull { byName[it.lowercase()] }
    return (liked + usable.sortedByDescending { it.songCount }).map { it.value }.distinct().take(count)
}

/** Pure: pick one genre's mix, favouring liked artists, starred and well-played songs. */
fun dailyMix(
    pool: List<SongDto>,
    profile: TasteProfile,
    rng: Random,
    size: Int = DAILY_MIX_SIZE,
    exclude: Set<String> = emptySet(),
): List<SongDto> {
    fun score(s: SongDto): Double {
        val rested = daysSince(s.played)?.let { it >= RESTED_DAYS } ?: true
        return 2.0 * (profile.artists[s.artistId] ?: 0.0) +
            (if (s.starred != null) 1.5 else 0.0) +
            0.3 * ln(1.0 + s.playCount) +
            rng.nextDouble() -
            (if (rested) 0.0 else 2.0)
    }
    val ranked = pool
        .filter { !it.isDir && it.id.isNotBlank() && it.id !in exclude }
        .distinctBy { it.id }
        .map { it to score(it) }
        .sortedByDescending { it.second }
        .map { it.first }
    val (known, unheard) = ranked.partition { it.playCount > 0 || it.starred != null }
    val unheardFirst = (size * UNHEARD_SHARE).toInt()
    val order = unheard.take(unheardFirst) + known + unheard.drop(unheardFirst)
    // Variety only counts while there's enough to choose from: a genre that is one album is still a mix.
    val picks = listOf(diversify(order, perArtist = 3, perAlbum = 2), diversify(order, perArtist = 6, perAlbum = 4))
        .firstOrNull { it.size >= size } ?: order
    return interleave(picks.take(size))
}

data class DailyMixUi(
    val mixes: List<DailyMix> = emptyList(),
    /** Empty until the user picks: the mixes then show their top genres. */
    val picked: List<String> = emptyList(),
    /** Everything the server has, for the picker. */
    val available: List<GenreDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** What survives a restart: the day's mixes stay the same until the day is over. */
@Serializable
private data class SavedMixes(
    val picked: List<String> = emptyList(),
    val pickedAt: Long = 0,
    val day: String? = null,
    val builtFrom: List<String> = emptyList(),
    val mixes: List<DailyMix> = emptyList(),
)

class DailyMixModel(
    private val api: SubsonicClient,
    private val file: File,
    private val scope: CoroutineScope,
    /** Called after the user picks, so other devices hear about it. */
    private val onPicked: () -> Unit = {},
    private val today: () -> String = { LocalDate.now().toString() },
) : SyncedMixGenres {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    @Volatile private var saved = load()
    private val _state = MutableStateFlow(DailyMixUi(mixes = saved.mixes, picked = saved.picked))
    val state: StateFlow<DailyMixUi> = _state.asStateFlow()
    private var job: Job? = null

    override val mixGenres: List<String> get() = saved.picked
    override val mixGenresAt: Long get() = saved.pickedAt

    /** Build if the saved mixes are from another day or from other genres. */
    fun ensureFresh() {
        if (job?.isActive == true) return
        if (saved.day != today() || saved.mixes.isEmpty() || saved.builtFrom != saved.picked) rebuild()
    }

    fun rebuild() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        val picked = saved.picked
        val day = today()
        job = scope.launch {
            runCatching { buildDailyMixes(api, picked, seed = "$day$picked".hashCode().toLong()) }
                .onSuccess { mixes ->
                    saved = saved.copy(day = day, builtFrom = picked, mixes = mixes)
                    persist()
                    _state.update { it.copy(mixes = mixes, loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach the server") } }
        }
    }

    /** For the picker. A failure here leaves the list empty, and the picker says so. */
    fun loadGenres() {
        scope.launch {
            val genres = runCatching { api.getGenres() }.getOrDefault(emptyList())
            _state.update { it.copy(available = genres.filter { g -> g.value.isNotBlank() }.sortedByDescending { g -> g.songCount }) }
        }
    }

    fun pick(genres: List<String>) {
        applyPicked(genres.take(MAX_MIX_GENRES), System.currentTimeMillis())
        onPicked()
    }

    override fun adoptMixGenres(genres: List<String>, at: Long) = applyPicked(genres, at)

    private fun applyPicked(genres: List<String>, at: Long) {
        saved = saved.copy(picked = genres, pickedAt = at)
        _state.update { it.copy(picked = genres) }
        rebuild()
    }

    fun clear() {
        job?.cancel()
        saved = SavedMixes()
        _state.value = DailyMixUi()
        scope.launch { withContext(Dispatchers.IO) { file.delete() } }
    }

    private fun load(): SavedMixes = runCatching {
        json.decodeFromString(SavedMixes.serializer(), file.readText())
    }.getOrDefault(SavedMixes())

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(SavedMixes.serializer(), saved))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
