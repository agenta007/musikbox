package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import app.musikbox.radio.RadioService
import app.musikbox.radio.RadioSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spotify radio through a real scout and Navidrome. Runs only with MB_TEST_SERVER (Navidrome,
 * admin / testpass) and MB_TEST_SCOUT (scout with spotify.firefox_cookies set) in the environment,
 * and a library holding Portishead - Roads (304 s) and some of the songs on its Spotify radio.
 */
class RadioServerTest {
    @Test fun roadsRadioFromSpotify() = runBlocking {
        val server = System.getenv("MB_TEST_SERVER")
        val scout = System.getenv("MB_TEST_SCOUT")
        assumeTrue("set MB_TEST_SERVER and MB_TEST_SCOUT to run", server != null && scout != null)
        val creds = Credentials.create(server!!, "admin", "testpass")
        val http = OkHttpClient()
        val api = SubsonicClient(http) { creds }
        val seed = api.search("Roads").first { it.title == "Roads" && it.artist == "Portishead" }
        val radio = RadioService(http, api, { creds }, { scout }).radio(seed.id, seed.artist, seed.title, seed.duration).getOrThrow()
        println(radio.message)
        radio.songs.forEach { println("  ${it.artist} - ${it.title}") }
        assertEquals(RadioSource.Spotify, radio.source)
        assertEquals("Roads Radio", radio.name)
        assertTrue(radio.spotifyMatched > 0)
        assertTrue(radio.songs.none { it.id == seed.id })
    }
}
