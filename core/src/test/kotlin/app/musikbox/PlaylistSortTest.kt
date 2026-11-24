package app.musikbox

import app.musikbox.data.PlaylistSort
import app.musikbox.data.PlaylistSortFields
import app.musikbox.data.playlistPlays
import app.musikbox.data.sortedForPlaylists
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PlaylistSortTest {
    private val before = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(before)

    private val lists = listOf(
        PlaylistSortFields("road trip", owner = "ann", created = "2024-03-01T10:00:00Z", changed = "2025-01-05T10:00:00Z",
            lastPlayedAt = 300, songCount = 40, durationSec = 9000),
        PlaylistSortFields("Ambient", owner = "bob", created = "2025-06-01T10:00:00Z", changed = "2025-06-02T10:00:00Z",
            lastPlayedAt = null, songCount = 12, durationSec = 4000),
        PlaylistSortFields("Émotions", owner = null, created = null, changed = "2024-01-01T10:00:00Z",
            lastPlayedAt = 900, songCount = 40, durationSec = 2000),
        PlaylistSortFields("Zebra", owner = "ann", created = "2023-01-01T10:00:00Z", changed = null,
            lastPlayedAt = 100, songCount = 3, durationSec = 600),
    )

    private fun names(sort: PlaylistSort, reversed: Boolean = false) =
        lists.sortedForPlaylists(sort, reversed) { it }.map { it.name }

    @Test fun byNameIgnoringCaseAndAccents() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(listOf("Ambient", "Émotions", "road trip", "Zebra"), names(PlaylistSort.Name))
        assertEquals(listOf("Zebra", "road trip", "Émotions", "Ambient"), names(PlaylistSort.Name, reversed = true))
    }

    @Test fun cyrillicInAlphabetOrderInAnyLanguage() {
        val bg = listOf("Яница", "бавни", "Вечер", "йога", "иво", "Ambient").map { PlaylistSortFields(it) }
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("bg"))) {
            Locale.setDefault(locale)
            assertEquals(listOf("Ambient", "бавни", "Вечер", "иво", "йога", "Яница"),
                bg.sortedForPlaylists(PlaylistSort.Name, false) { it }.map { it.name })
            assertEquals(listOf("Яница", "йога", "иво", "Вечер", "бавни", "Ambient"),
                bg.sortedForPlaylists(PlaylistSort.Name, true) { it }.map { it.name })
        }
    }

    @Test fun datesNewestFirstAndMissingLastBothWays() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(listOf("Ambient", "road trip", "Zebra", "Émotions"), names(PlaylistSort.Added))
        assertEquals(listOf("Zebra", "road trip", "Ambient", "Émotions"), names(PlaylistSort.Added, reversed = true))
        assertEquals(listOf("Ambient", "road trip", "Émotions", "Zebra"), names(PlaylistSort.Updated))
        assertEquals(listOf("Émotions", "road trip", "Zebra", "Ambient"), names(PlaylistSort.Played))
        assertEquals(listOf("Zebra", "road trip", "Émotions", "Ambient"), names(PlaylistSort.Played, reversed = true))
    }

    @Test fun sizesBiggestFirstTiesByName() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(listOf("Émotions", "road trip", "Ambient", "Zebra"), names(PlaylistSort.Songs))
        assertEquals(listOf("Zebra", "Ambient", "Émotions", "road trip"), names(PlaylistSort.Songs, reversed = true))
        assertEquals(listOf("road trip", "Ambient", "Émotions", "Zebra"), names(PlaylistSort.Length))
    }

    @Test fun byOwnerThenName() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(listOf("road trip", "Zebra", "Ambient", "Émotions"), names(PlaylistSort.Owner))
        assertEquals(listOf("Ambient", "road trip", "Zebra", "Émotions"), names(PlaylistSort.Owner, reversed = true))
    }

    @Test fun mostPlayedFromServerCountsAndPlaysSince() {
        Locale.setDefault(Locale.ENGLISH)
        val lists = listOf(
            PlaylistSortFields("Gym", plays = playlistPlays(serverPlays = 120, localPlays = 3)),
            PlaylistSortFields("Sleep", plays = playlistPlays(serverPlays = null, localPlays = 0)),
            PlaylistSortFields("Car", plays = playlistPlays(serverPlays = 90, localPlays = 40)),
            PlaylistSortFields("Abba", plays = playlistPlays(serverPlays = 123, localPlays = 0)),
        )
        val names = { r: Boolean -> lists.sortedForPlaylists(PlaylistSort.Plays, r) { it }.map { it.name } }
        assertEquals(listOf("Car", "Abba", "Gym", "Sleep"), names(false)) // 130, 123 = 123 ties by name, 0
        assertEquals(listOf("Sleep", "Abba", "Gym", "Car"), names(true))
    }

    @Test fun storedNames() {
        assertEquals(PlaylistSort.Added, PlaylistSort.parse("Added"))
        assertEquals(PlaylistSort.Name, PlaylistSort.parse("Size")) // an old or unknown name
        assertEquals(PlaylistSort.Name, PlaylistSort.parse(null))
    }
}
