package app.musikbox.discover

import app.musikbox.data.remote.AlbumDto
import app.musikbox.data.remote.Starred2Dto
import app.musikbox.data.remote.SongDto
import app.musikbox.data.remote.SubsonicClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.ln
import kotlin.random.Random

/**
 * "Discover new music from your own library."
 *
 * Navidrome ships the raw material (play counts, last-played dates, stars, genres, random and
 * newest album lists, similar songs from its Deezer / ListenBrainz / Last.fm / AudioMuse agents)
 * but no recommender. This builds one on top:
 *
 *  1. **Taste profile**: artist and genre affinities from what you play most, played recently
 *     and starred (log-scaled so one binge doesn't dominate).
 *  2. **Candidates**, each from a different angle: similar songs to your favourites, unplayed
 *     songs in your top genres, unheard albums by artists you love, newly added albums,
 *     long-unplayed favourites, and wildcards outside your usual genres.
 *  3. **Mix**: only songs you have never played, scored by affinity + source + a little
 *     randomness, then diversified (≤ 2 per artist and album, relaxed to 4/3 if that leaves the
 *     mix short; no same artist back to back). A shorter honest mix beats padding it with played songs.
 */
class DiscoverEngine(private val api: SubsonicClient) {

    suspend fun build(seed: Long = System.currentTimeMillis(), mixSize: Int = 40): DiscoverResult = coroutineScope {
        val rng = Random(seed)
        // Round 1: history + broad candidate pools, all in parallel.
        val frequentD = async { runCatching { api.getAlbumList2("frequent", 50) }.getOrDefault(emptyList()) }
        val recentD = async { runCatching { api.getAlbumList2("recent", 50) }.getOrDefault(emptyList()) }
        val starredD = async { runCatching { api.getStarred2() }.getOrDefault(Starred2Dto()) }
        val newestD = async { runCatching { api.getAlbumList2("newest", 60) }.getOrDefault(emptyList()) }
        val randomD = async { runCatching { api.getAlbumList2("random", 80) }.getOrDefault(emptyList()) }
        val frequent = frequentD.await()
        val recent = recentD.await()
        val starred = starredD.await()
        val profile = buildProfile(frequent, recent, starred)

        // Seeds for "similar": starred songs, topped up with your top artists' most-played songs.
        val starredSeeds = starred.song.shuffled(rng).take(4)
        val topSeedsD = if (starredSeeds.size >= 3) emptyList() else profile.topArtists.take(3)
            .filter { it.second.isNotBlank() }
            .map { (_, name) -> async { runCatching { api.getTopSongs(name, 3) }.getOrDefault(emptyList()).firstOrNull() } }
        val seeds = (starredSeeds + topSeedsD.awaitAll().filterNotNull()).distinctBy { it.id }.take(5)

        // Round 2: targeted lookups seeded by the profile.
        val similarD = seeds.map { s ->
            async { runCatching { api.getSimilarSongs2(s.id, 25) }.getOrDefault(emptyList()) }
        }
        val genreD = profile.topGenres.take(3).map { g ->
            async { g to runCatching { api.getRandomSongs(60, genre = g) }.getOrDefault(emptyList()) }
        }
        val artistD = profile.topArtists.take(5).map { (id, _) ->
            async { runCatching { api.getArtist(id).album }.getOrDefault(emptyList()) }
        }
        val similar = similarD.awaitAll().flatten().distinctBy { it.id }
        val byGenre = genreD.awaitAll()
        val artistAlbums = artistD.awaitAll().flatten().distinctBy { it.id }
        val newest = newestD.await()
        val random = randomD.await()

        val unheardByFavourites = artistAlbums.filter { it.playCount == 0L }
        val fresh = newest.filter { it.playCount == 0L }
        val rediscover = (starred.album + frequent).distinctBy { it.id }
            .filter { it.playCount > 0 && (daysSince(it.played) ?: 0) >= REDISCOVER_AFTER_DAYS }
        val shownAlbums = (unheardByFavourites + fresh).map { it.id }.toSet()
        val wildcards = random.filter { it.playCount == 0L && it.genre !in profile.topGenres.take(3) && it.id !in shownAlbums }

        // Round 3: pull a few unheard albums' tracks so the mix isn't only "similar" + genre picks.
        val albumTracksD = (unheardByFavourites.take(3) + fresh.take(3)).distinctBy { it.id }.map { a ->
            async { runCatching { api.getAlbum(a.id).song }.getOrDefault(emptyList()) }
        }
        val albumTracks = albumTracksD.awaitAll().flatten()

        val candidates = buildList {
            similar.forEach { add(Candidate(it, Source.Similar)) }
            byGenre.forEach { (_, songs) -> songs.forEach { add(Candidate(it, Source.Genre)) } }
            albumTracks.forEach { add(Candidate(it, Source.UnheardAlbum)) }
        }
        val mix = rankMix(candidates, profile, rng, mixSize)

        val sections = buildList {
            if (similar.isNotEmpty()) add(
                DiscoverSection(
                    "similar", "Sounds like your favourites",
                    "Similar songs, according to your server's music agents",
                    songs = diversify(similar.filter { it.playCount == 0L }.ifEmpty { similar }, 1, 2).take(12),
                ),
            )
            if (unheardByFavourites.isNotEmpty()) add(
                DiscoverSection(
                    "artists", "More from artists you love",
                    "Albums by your most-played artists you haven't heard",
                    albums = unheardByFavourites.shuffled(rng).take(12),
                ),
            )
            byGenre.filter { (_, s) -> s.any { it.playCount == 0L } }.take(2).forEach { (genre, songs) ->
                add(
                    DiscoverSection(
                        "genre:$genre", "Unplayed $genre",
                        "Deep cuts from one of your top genres",
                        songs = diversify(songs.filter { it.playCount == 0L }, 3, 2).take(10),
                    ),
                )
            }
            if (fresh.isNotEmpty()) add(
                DiscoverSection("fresh", "New in your library", "Recently added, not played yet", albums = fresh.take(12)),
            )
            if (rediscover.isNotEmpty()) add(
                DiscoverSection(
                    "rediscover", "Rediscover",
                    "Albums you loved but haven't played in $REDISCOVER_AFTER_DAYS+ days",
                    albums = rediscover.shuffled(rng).take(12),
                ),
            )
            if (wildcards.isNotEmpty()) add(
                DiscoverSection("wildcards", "Outside your comfort zone", "Never played, outside your usual genres", albums = wildcards.take(12)),
            )
        }
        DiscoverResult(mix, sections, profile, generatedAt = System.currentTimeMillis())
    }

    companion object {
        const val REDISCOVER_AFTER_DAYS = 60L
    }
}

data class DiscoverSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<SongDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
)

data class DiscoverResult(
    val mix: List<SongDto>,
    val sections: List<DiscoverSection>,
    val profile: TasteProfile,
    val generatedAt: Long,
)

data class TasteProfile(
    /** artistId → affinity (0..1). */
    val artists: Map<String, Double>,
    /** genre → affinity (0..1). */
    val genres: Map<String, Double>,
    /** (artistId, name), strongest first. */
    val topArtists: List<Pair<String, String>>,
    val topGenres: List<String>,
) {
    val isEmpty get() = artists.isEmpty() && genres.isEmpty()
}

enum class Source(val bonus: Double) { Similar(1.0), UnheardAlbum(0.6), Genre(0.3) }

data class Candidate(val song: SongDto, val source: Source)

/** Pure: affinities from play counts, recency and stars, normalised to 0..1. */
fun buildProfile(frequent: List<AlbumDto>, recent: List<AlbumDto>, starred: Starred2Dto): TasteProfile {
    val artist = HashMap<String, Double>()
    val names = HashMap<String, String>()
    val genre = HashMap<String, Double>()
    fun add(artistId: String?, artistName: String?, g: String?, w: Double) {
        if (artistId != null) {
            artist.merge(artistId, w, Double::plus)
            artistName?.let { names.putIfAbsent(artistId, it) }
        }
        if (!g.isNullOrBlank()) genre.merge(g, w, Double::plus)
    }
    frequent.forEach { add(it.artistId, it.artist, it.genre, ln(1.0 + it.playCount)) }
    recent.forEachIndexed { i, a -> add(a.artistId, a.artist, a.genre, 1.0 / (1 + i * 0.25)) }
    starred.song.forEach { add(it.artistId, it.artist, it.genre, 1.5) }
    starred.album.forEach { add(it.artistId, it.artist, it.genre, 2.0) }
    starred.artist.forEach { add(it.id, it.name, null, 3.0) }

    fun Map<String, Double>.normalised(): Map<String, Double> {
        val max = values.maxOrNull() ?: return emptyMap()
        return mapValues { it.value / max }
    }
    val a = artist.normalised()
    val g = genre.normalised()
    return TasteProfile(
        artists = a,
        genres = g,
        topArtists = a.entries.sortedByDescending { it.value }.map { it.key to (names[it.key] ?: "") },
        topGenres = g.entries.sortedByDescending { it.value }.map { it.key },
    )
}

/** Pure: score never-played candidates and pick a diverse, interleaved mix. */
fun rankMix(candidates: List<Candidate>, profile: TasteProfile, rng: Random, size: Int): List<SongDto> {
    val best = HashMap<String, Pair<Candidate, Double>>()
    candidates.forEach { c ->
        val s = c.song
        if (s.isDir || s.id.isBlank() || s.playCount > 0) return@forEach
        val score = 2.0 * (profile.artists[s.artistId] ?: 0.0) +
            1.5 * (profile.genres[s.genre] ?: 0.0) +
            c.source.bonus +
            0.8 * rng.nextDouble()
        val prev = best[s.id]
        if (prev == null || prev.second < score) best[s.id] = c to score
    }
    val ranked = best.values.sortedByDescending { it.second }.map { it.first.song }
    // Choose the best [size] first, then order them — interleaving must not change *which* songs win.
    val strict = diversify(ranked, perArtist = 2, perAlbum = 2)
    val picked = if (strict.size >= size) strict else diversify(ranked, perArtist = 4, perAlbum = 3)
    return interleave(picked.take(size))
}

/** Keeps order, but caps how many songs one artist / album may contribute. */
fun diversify(songs: List<SongDto>, perArtist: Int, perAlbum: Int): List<SongDto> {
    val artists = HashMap<String, Int>()
    val albums = HashMap<String, Int>()
    return songs.filter { s ->
        val ak = s.artistId ?: s.artist ?: s.id
        val alk = s.albumId ?: s.album ?: s.id
        val okArtist = (artists[ak] ?: 0) < perArtist
        val okAlbum = (albums[alk] ?: 0) < perAlbum
        if (okArtist && okAlbum) {
            artists.merge(ak, 1, Int::plus); albums.merge(alk, 1, Int::plus); true
        } else false
    }
}

/**
 * Avoid the same artist twice in a row: at each step take, among songs by a different artist
 * than the last, the one whose artist has the most songs left (ties keep rank order).
 */
fun interleave(songs: List<SongDto>): List<SongDto> {
    fun key(s: SongDto) = s.artistId ?: s.artist ?: s.id
    val pending = songs.toMutableList()
    val remaining = HashMap<String, Int>().apply { songs.forEach { merge(key(it), 1, Int::plus) } }
    val out = ArrayList<SongDto>(songs.size)
    while (pending.isNotEmpty()) {
        val last = out.lastOrNull()?.let(::key)
        var pick = -1
        pending.forEachIndexed { i, s ->
            if (key(s) == last) return@forEachIndexed
            if (pick < 0 || remaining.getValue(key(s)) > remaining.getValue(key(pending[pick]))) pick = i
        }
        if (pick < 0) pick = 0
        val s = pending.removeAt(pick)
        remaining.merge(key(s), -1, Int::plus)
        out += s
    }
    return out
}

fun daysSince(iso: String?): Long? = runCatching {
    Duration.between(OffsetDateTime.parse(iso).toInstant(), Instant.now()).toDays()
}.getOrNull()

/** Short caption for an album card: "Never played · 2021 · Ambient" or "Last played 3 mo ago". */
fun albumNote(a: AlbumDto): String = when {
    a.playCount == 0L -> (listOf("Never played") + listOfNotNull(a.year?.toString(), a.genre)).joinToString(" · ")
    else -> daysSince(a.played)?.let { d ->
        when {
            d >= 365 -> "Last played ${d / 365} yr ago"
            d >= 30 -> "Last played ${d / 30} mo ago"
            else -> "Last played $d days ago"
        }
    } ?: "Played ${a.playCount}×"
}
