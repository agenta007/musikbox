package app.musikbox

import app.musikbox.data.remote.GenreDto
import app.musikbox.data.remote.SongDto
import app.musikbox.discover.TasteProfile
import app.musikbox.discover.dailyMix
import app.musikbox.discover.defaultGenres
import app.musikbox.sync.CloudDoc
import app.musikbox.sync.CloudSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class DailyMixTest {
    private val noTaste = TasteProfile(emptyMap(), emptyMap(), emptyList(), emptyList())

    private fun song(n: Int, artist: Int = n, plays: Long = 0, starred: Boolean = false, played: String? = null) = SongDto(
        id = "s$n", title = "Song $n", artistId = "a$artist", artist = "Artist $artist", albumId = "alb$n",
        playCount = plays, starred = if (starred) "2026-01-01T00:00:00Z" else null, played = played,
    )

    @Test fun keepsRoomForUnheardSongs() {
        val pool = (1..60).map { song(it, plays = 5) } + (61..120).map { song(it) }
        val mix = dailyMix(pool, noTaste, Random(1), size = 40)
        assertEquals(40, mix.size)
        assertEquals(12, mix.count { it.playCount == 0L })
    }

    @Test fun capsEachArtistWhenThereIsChoice() {
        val pool = (1..30).map { song(it, artist = 1, plays = 9) } + (31..120).map { song(it, plays = 1) }
        val mix = dailyMix(pool, noTaste, Random(2), size = 30)
        assertTrue(mix.count { it.artistId == "a1" } <= 3)
    }

    @Test fun aGenreThatIsOneAlbumStillGivesAMix() {
        val pool = (1..10).map { song(it, artist = 1).copy(albumId = "only") }
        assertEquals(10, dailyMix(pool, noTaste, Random(3), size = 40).size)
    }

    @Test fun songsPlayedRecentlySinkBelowRestedOnes() {
        val yesterday = OffsetDateTime.now().minusDays(1).toString()
        val tired = (1..20).map { song(it, plays = 3, played = yesterday) }
        val rested = (21..40).map { song(it, plays = 3) }
        val mix = dailyMix(tired + rested, noTaste, Random(4), size = 20)
        assertTrue(mix.count { it.id in tired.map { t -> t.id } } == 0)
    }

    @Test fun skipsSongsAnotherMixAlreadyUsed() {
        val pool = (1..20).map { song(it) }
        val mix = dailyMix(pool, noTaste, Random(5), size = 20, exclude = setOf("s1", "s2"))
        assertFalse(mix.any { it.id == "s1" || it.id == "s2" })
    }

    @Test fun defaultsToYourTopGenresThenTheBiggest() {
        val taste = noTaste.copy(topGenres = listOf("jazz", "Unknown"))
        val available = listOf(GenreDto("Rock", 500), GenreDto("Jazz", 50), GenreDto("Pop", 300), GenreDto("Tiny", 2))
        assertEquals(listOf("Jazz", "Rock", "Pop"), defaultGenres(taste, available))
    }

    @Test fun theNewestGenrePickWinsWhenDocsMerge() {
        val old = CloudDoc(mixGenres = listOf("Rock"), mixGenresAt = 10)
        val new = CloudDoc(mixGenres = listOf("Jazz"), mixGenresAt = 20)
        assertEquals(listOf("Jazz"), CloudSync.mergeDocs(old, new).mixGenres)
        assertEquals(listOf("Jazz"), CloudSync.mergeDocs(new, old).mixGenres)
        assertEquals(20, CloudSync.mergeDocs(old, new).mixGenresAt)
    }
}
