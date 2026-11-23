package app.musikbox.radio

import app.musikbox.data.nameSortKey
import java.io.BufferedReader
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.async

/** An internet radio station from the bundled catalog (Radio Browser, by country). */
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val logo: String?,
    val countryCode: String,
    val codec: String,
    val bitrate: Int,
    /** An HLS (.m3u8) stream rather than a plain Icecast/Shoutcast one. */
    val hls: Boolean,
    val genres: List<String>,
    /** Radio Browser votes: how popular the station is. */
    val votes: Int,
) {
    /** "MP3 128k", "AAC", or "" when unknown. */
    val quality: String
        get() = listOfNotNull(codec.takeIf { it.isNotBlank() }, bitrate.takeIf { it > 0 }?.let { "${it}k" }).joinToString(" ")
}

data class RadioCountry(val code: String, val name: String, val stations: Int) {
    /** The country's flag as an emoji ("BG" -> 🇧🇬); fonts without flags show the two letters. */
    val flag: String get() = flagOf(code)
}

data class RadioGenre(val name: String, val stations: Int)

/** What a station list is: everything in a country, a genre, the favourites, or a search. */
sealed interface RadioCategory {
    val key: String

    data class Country(val code: String) : RadioCategory { override val key get() = "country:$code" }
    data class Genre(val name: String) : RadioCategory { override val key get() = "genre:$name" }
    data object Favorites : RadioCategory { override val key get() = "favorites" }

    companion object {
        fun parse(key: String?): RadioCategory? = when {
            key == null -> null
            key == "favorites" -> Favorites
            key.startsWith("country:") -> Country(key.removePrefix("country:"))
            key.startsWith("genre:") -> Genre(key.removePrefix("genre:"))
            else -> null
        }
    }
}

/**
 * The stations bundled with the app (`app/musikbox/radio/stations.tsv.gz`, made by
 * tools/build_radio_catalog.py). Load it once, off the main thread: about 32,000 stations.
 */
class RadioCatalog private constructor(
    val countries: List<RadioCountry>,
    val genres: List<RadioGenre>,
    val stations: List<RadioStation>,
    /** When the catalog was generated (yyyy-mm-dd). */
    val generated: String,
) {
    private val byId = stations.associateBy { it.id }
    private val byCountry = stations.groupBy { it.countryCode }
    private val countryNames = countries.associate { it.code to it.name }
    private val searchKeys by lazy { stations.map { nameSortKey(it.name) } }

    fun station(id: String): RadioStation? = byId[id]

    fun countryName(code: String): String = countryNames[code] ?: code

    fun country(code: String): RadioCountry? = countries.firstOrNull { it.code == code }

    /** A country's stations, most listened first. */
    fun inCountry(code: String): List<RadioStation> = byCountry[code].orEmpty()

    /** A genre's stations: [homeCountry]'s first, then the rest of the world, most voted first. */
    fun inGenre(genre: String, homeCountry: String?): List<RadioStation> {
        val all = stations.filter { genre in it.genres }
        val home = all.filter { it.countryCode == homeCountry }
        return home + (all - home.toSet()).sortedByDescending { it.votes }
    }

    /** These ids' stations, in the given order; ids no longer in the catalog are skipped. */
    fun stationsFor(ids: List<String>): List<RadioStation> = ids.mapNotNull { byId[it] }

    fun stations(category: RadioCategory, favorites: List<String>, homeCountry: String?): List<RadioStation> = when (category) {
        is RadioCategory.Country -> inCountry(category.code)
        is RadioCategory.Genre -> inGenre(category.name, homeCountry)
        RadioCategory.Favorites -> stationsFor(favorites)
    }

    fun title(category: RadioCategory): String = when (category) {
        is RadioCategory.Country -> countryName(category.code)
        is RadioCategory.Genre -> category.name
        RadioCategory.Favorites -> "Favourites"
    }

    /**
     * Stations whose name has every word of [query] (case, accents aside), the most voted first;
     * stations of [homeCountry] rank ahead of others.
     */
    fun search(query: String, homeCountry: String?, limit: Int = 100): List<RadioStation> {
        val words = nameSortKey(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return stations.indices
            .filter { i -> words.all { it in searchKeys[i] } }
            .map { stations[it] }
            .sortedWith(compareByDescending<RadioStation> { it.countryCode == homeCountry }.thenByDescending { it.votes })
            .take(limit)
    }

    /** The countries with [homeCountry] first, then by name. */
    fun countriesFor(homeCountry: String?): List<RadioCountry> {
        val home = countries.filter { it.code == homeCountry }
        return home + countries.filter { it.code != homeCountry }
    }

    companion object {
        const val RESOURCE = "/app/musikbox/radio/stations.tsv.gz"

        /** The bundled catalog. Blocking: call it from a background thread. */
        fun load(): RadioCatalog {
            val stream = RadioCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: error("The radio station list is missing from the app")
            return parse(GZIPInputStream(stream, 1 shl 16))
        }

        fun parse(input: InputStream): RadioCatalog = input.bufferedReader(Charsets.UTF_8).use(::parse)

        private fun parse(reader: BufferedReader): RadioCatalog {
            val countryNames = LinkedHashMap<String, String>()
            val genreNames = ArrayList<String>()
            val stations = ArrayList<RadioStation>(32_000)
            var generated = ""
            reader.forEachLine { line ->
                val f = line.split('\t')
                when (f[0]) {
                    "#musikbox-radio" -> generated = f.getOrElse(2) { "" }
                    "C" -> if (f.size >= 3) countryNames[f[1]] = f[2]
                    "G" -> if (f.size >= 3) genreNames += f[2]
                    "S" -> if (f.size >= 11) stations += RadioStation(
                        id = f[1],
                        name = f[2],
                        url = f[3],
                        logo = f[4].ifBlank { null },
                        countryCode = f[5],
                        codec = f[6],
                        bitrate = f[7].toIntOrNull() ?: 0,
                        hls = f[8] == "1",
                        genres = if (f[9].isEmpty()) emptyList() else f[9].split(',').mapNotNull { genreNames.getOrNull(it.toIntOrNull() ?: -1) },
                        votes = f[10].toIntOrNull() ?: 0,
                    )
                }
            }
            val perCountry = stations.groupingBy { it.countryCode }.eachCount()
            val perGenre = stations.flatMap { it.genres }.groupingBy { it }.eachCount()
            return RadioCatalog(
                countries = countryNames.map { (code, name) -> RadioCountry(code, name, perCountry[code] ?: 0) }
                    .filter { it.stations > 0 }
                    .sortedBy { nameSortKey(it.name) },
                genres = genreNames.map { RadioGenre(it, perGenre[it] ?: 0) }.filter { it.stations > 0 },
                stations = stations,
                generated = generated,
            )
        }

        /** The device's country, when it is one the catalog has stations for. */
        fun localeCountry(locale: Locale = Locale.getDefault()): String? = locale.country.uppercase().takeIf { it.length == 2 }
    }
}

/** Two regional-indicator letters make a flag emoji. */
fun flagOf(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return code
    return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/**
 * The FM-style dial under the station card: a list's stations spread over 87.5–108 MHz, so
 * swiping to the next station turns the dial. Purely decorative; internet radio has no band.
 */
object RadioDial {
    const val LOW = 87.5
    const val HIGH = 108.0

    /** Station [index] of [count]'s place on the dial, in MHz. */
    fun frequency(index: Float, count: Int): Double =
        if (count <= 1) 98.0 else LOW + (HIGH - LOW) * (index.coerceIn(0f, (count - 1).toFloat()) / (count - 1))

    /** "98.4" for lists that fit the 0.1 MHz grid, "98.42" for longer ones so neighbours differ. */
    fun label(index: Int, count: Int): String {
        val mhz = frequency(index.toFloat(), count)
        return if (count <= 206) String.format(Locale.ROOT, "%.1f", mhz) else String.format(Locale.ROOT, "%.2f", mhz)
    }
}

/** Loads the bundled [RadioCatalog] once, in the background, the first time a screen needs it. */
class RadioLibrary(private val scope: kotlinx.coroutines.CoroutineScope) {
    private val _catalog = kotlinx.coroutines.flow.MutableStateFlow<RadioCatalog?>(null)
    val catalog: kotlinx.coroutines.flow.StateFlow<RadioCatalog?> = _catalog
    private var loading: kotlinx.coroutines.Deferred<RadioCatalog>? = null

    @Synchronized
    private fun start(): kotlinx.coroutines.Deferred<RadioCatalog> = loading ?: scope.async(kotlinx.coroutines.Dispatchers.IO) {
        RadioCatalog.load().also { _catalog.value = it }
    }.also { loading = it }

    fun ensureLoaded() { start() }

    suspend fun await(): RadioCatalog = start().await()
}

/** [ids] with [id] added at the end, or taken out when it is there. */
fun toggledFavorite(ids: List<String>, id: String): List<String> = if (id in ids) ids - id else ids + id

/** "1 station", "1,000 stations". */
fun stationCount(n: Int): String = if (n == 1) "1 station" else String.format(Locale.ROOT, "%,d stations", n)
