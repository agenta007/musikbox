package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import app.musikbox.radio.RadioService
import app.musikbox.radio.RadioSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Song radio against a fake scout and a fake Subsonic server, both on one local port. */
class RadioTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private var scoutStatus = 200
    private var scoutBody = ""
    private var similarIds = listOf<String>()
    private val scoutRequests = mutableListOf<Pair<String?, String>>() // X-Scout header, body
    private var similarCalls = 0

    private fun song(id: String) = """{"id":"$id","title":"Song $id","artist":"Artist $id","duration":200}"""

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/scout/api/radio") { ex ->
            scoutRequests += ex.requestHeaders.getFirst("X-Scout") to ex.requestBody.readBytes().decodeToString()
            val out = scoutBody.toByteArray()
            ex.sendResponseHeaders(scoutStatus, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.createContext("/rest/getSimilarSongs2.view") { ex ->
            similarCalls++
            val out = """{"subsonic-response":{"status":"ok","version":"1.16.1","similarSongs2":{"song":[${similarIds.joinToString(",") { song(it) }}]}}}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun tearDown() = server.stop(0)

    private fun service(scout: String?): RadioService {
        val creds = Credentials(base, "ann", "abc123", "0123456789abcdef0123456789abcdef")
        val http = OkHttpClient()
        return RadioService(http, SubsonicClient(http) { creds }, { creds }, { scout })
    }

    private fun scoutSays(vararg ids: String, total: Int = 50, downloading: Boolean = false) {
        scoutBody = """{"name":"Roads Radio","source":"spotify","songs":[${ids.joinToString(",") { song(it) }}],"total":$total,"missing":[],"downloading":$downloading}"""
    }

    @Test fun spotifyRadioThroughScout() = runBlocking {
        scoutSays("seed", *(1..12).map { "s$it" }.toTypedArray())
        val radio = service("$base/scout/").radio("seed", "Portishead", "Roads", 304).getOrThrow()
        assertEquals(RadioSource.Spotify, radio.source)
        assertEquals("Roads Radio", radio.name)
        assertEquals((1..12).map { "s$it" }, radio.songs.map { it.id })
        assertEquals(12, radio.spotifyMatched)
        assertFalse(radio.toppedUp)
        assertEquals(0, similarCalls)
        assertEquals("Roads Radio: 12 of 50 songs are in your library", radio.message)
        val (header, body) = scoutRequests.single()
        assertEquals("1", header)
        for (part in listOf("\"u\":\"ann\"", "\"t\":\"0123456789abcdef0123456789abcdef\"", "\"s\":\"abc123\"",
                "\"artist\":\"Portishead\"", "\"title\":\"Roads\"", "\"seconds\":304")) {
            assertTrue("$part in $body", part in body)
        }
    }

    @Test fun fewSpotifySongsAreToppedUpFromTheServer() = runBlocking {
        scoutSays("s1", "s2", "s3")
        similarIds = listOf("seed", "s2", "n1", "n2")
        val radio = service("$base/scout").radio("seed", "Portishead", "Roads", 304).getOrThrow()
        assertEquals(listOf("s1", "s2", "s3", "n1", "n2"), radio.songs.map { it.id })
        assertTrue(radio.toppedUp)
        assertEquals("Roads Radio: 3 of 50 songs are in your library, then similar songs from your server", radio.message)
    }

    @Test fun scoutTroubleFallsBackToTheServer() = runBlocking {
        scoutStatus = 502
        scoutBody = """{"error":"Spotify doesn't have Portishead - Roads"}"""
        similarIds = listOf("n1", "seed", "n2")
        val radio = service("$base/scout").radio("seed", "Portishead", "Roads", 304).getOrThrow()
        assertEquals(RadioSource.Server, radio.source)
        assertEquals(listOf("n1", "n2"), radio.songs.map { it.id })
        assertEquals("Spotify doesn't have Portishead - Roads", radio.spotifyProblem)
    }

    @Test fun noneOfSpotifysSongsHere() = runBlocking {
        scoutSays("seed")
        similarIds = listOf("n1")
        val radio = service("$base/scout").radio("seed", "Portishead", "Roads", 304).getOrThrow()
        assertEquals(RadioSource.Server, radio.source)
        assertEquals("none of its songs are in your library", radio.spotifyProblem)
    }

    @Test fun withoutScoutItsTheServersSimilarSongs() = runBlocking {
        similarIds = listOf("n1", "n2")
        val radio = service(null).radio("seed", "Portishead", "Roads", 304).getOrThrow()
        assertEquals(RadioSource.Server, radio.source)
        assertNull(radio.spotifyProblem)
        assertTrue(scoutRequests.isEmpty())
        assertEquals("Roads Radio: similar songs from your server", radio.message)
    }

    @Test fun nothingAnywhereIsAnError() = runBlocking {
        val result = service(null).radio("seed", "Portishead", "Roads", 304)
        assertTrue(result.isFailure)
        assertEquals("Your server knows no songs similar to Roads", result.exceptionOrNull()?.message)
    }

    @Test fun scoutAddresses() {
        assertEquals("http://192.168.1.5:4535/api/radio", RadioService.scoutRadioUrl("192.168.1.5:4535"))
        assertEquals("https://music.example.com/scout/api/radio", RadioService.scoutRadioUrl(" https://music.example.com/scout/ "))
        assertNull(RadioService.scoutRadioUrl(""))
        assertNull(RadioService.scoutRadioUrl("http://"))
    }
}
