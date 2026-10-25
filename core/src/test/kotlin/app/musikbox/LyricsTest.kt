package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import app.musikbox.lyrics.FileLyricsCache
import app.musikbox.lyrics.LrcParser
import app.musikbox.lyrics.LyricLine
import app.musikbox.lyrics.Lyrics
import app.musikbox.lyrics.LyricsQuery
import app.musikbox.lyrics.LyricsResult
import app.musikbox.lyrics.LyricsService
import app.musikbox.lyrics.LyricsSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LyricsTest {

    @Test fun parsesLrcWithOffsetsRepeatsAndWordStamps() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [offset:+500]
            [00:12.30]First <00:12.50>line
            [00:05.00][01:00.5]Chorus
            [00:20.000]
            plain text without a stamp
            """.trimIndent(),
        )
        assertEquals(
            listOf(LyricLine(4_500, "Chorus"), LyricLine(11_800, "First line"), LyricLine(19_500, ""), LyricLine(60_000, "Chorus")),
            lines,
        )
    }

    @Test fun lineAtFindsTheLineBeingSung() {
        val l = Lyrics(LyricsSource.Server, true, listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c")))
        assertEquals(-1, l.lineAt(999))
        assertEquals(0, l.lineAt(1_000))
        assertEquals(1, l.lineAt(8_999))
        assertEquals(2, l.lineAt(600_000))
        assertEquals(-1, l.copy(synced = false).lineAt(5_000))
    }

    // ---- service: a fake Subsonic server and a fake LRCLIB on one local HTTP server

    private lateinit var http: HttpServer
    private val subsonicCalls = AtomicInteger()
    private val lrclibCalls = AtomicInteger()
    private var serverLyrics = """{"structuredLyrics":[]}"""
    private var lrclibStatus = 404
    private lateinit var base: String

    @Before fun startFakes() {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/rest/getLyricsBySongId.view") { ex ->
            subsonicCalls.incrementAndGet()
            ex.reply(200, """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":$serverLyrics}}""")
        }
        http.createContext("/api/get") { ex ->
            lrclibCalls.incrementAndGet()
            if (lrclibStatus == 404) ex.reply(404, """{"code":404}""")
            else ex.reply(200, """{"duration":200.0,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00]Hello\n[00:02.00]World"}""")
        }
        http.start()
        base = "http://127.0.0.1:${http.address.port}"
    }

    @After fun stopFakes() = http.stop(0)

    private fun com.sun.net.httpserver.HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun service(lrclib: String?): LyricsService {
        val creds = Credentials.create(base, "u", "p")
        val client = OkHttpClient()
        return LyricsService(SubsonicClient(client) { creds }, client, FileLyricsCache(Files.createTempDirectory("lyr").toFile()), { lrclib }, "musikbox-test")
    }

    private val song = LyricsQuery("s1", "Title", "Artist", "Album", 200)

    @Test fun serverLyricsWinAndLrclibIsNotAsked() = runBlocking {
        serverLyrics = """{"structuredLyrics":[
            {"lang":"eng","synced":false,"line":[{"value":"plain one"}]},
            {"lang":"eng","synced":true,"offset":100,"line":[{"start":1100,"value":"synced one"},{"start":3100,"value":"two"}]}]}"""
        lrclibStatus = 200
        val r = service(base).load(song) as LyricsResult.Found
        assertEquals(LyricsSource.Server, r.lyrics.source)
        assertTrue(r.lyrics.synced)
        assertEquals(listOf(LyricLine(1_000, "synced one"), LyricLine(3_000, "two")), r.lyrics.lines)
        assertEquals(0, lrclibCalls.get())
    }

    @Test fun lrclibIsNeverAskedWhileDisabled() = runBlocking {
        lrclibStatus = 200
        assertEquals(LyricsResult.None, service(null).load(song))
        assertEquals(0, lrclibCalls.get())
    }

    @Test fun fallsBackToLrclibAndCachesTheAnswer() = runBlocking {
        lrclibStatus = 200
        val s = service(base)
        val r = s.load(song) as LyricsResult.Found
        assertEquals(LyricsSource.Lrclib, r.lyrics.source)
        assertEquals(listOf(LyricLine(1_000, "Hello"), LyricLine(2_000, "World")), r.lyrics.lines)
        s.load(song)
        assertEquals("second load comes from the cache", 1, subsonicCalls.get())
        assertEquals(1, lrclibCalls.get())
    }

    @Test fun noneIsCachedButEnablingLrclibAsksAgain() = runBlocking {
        val creds = Credentials.create(base, "u", "p")
        val client = OkHttpClient()
        val cache = FileLyricsCache(Files.createTempDirectory("lyr").toFile())
        var online: String? = null
        val s = LyricsService(SubsonicClient(client) { creds }, client, cache, { online }, "musikbox-test")
        assertEquals(LyricsResult.None, s.load(song))
        assertEquals(LyricsResult.None, s.load(song))
        assertEquals(1, subsonicCalls.get())
        online = base
        lrclibStatus = 200
        assertTrue(s.load(song) is LyricsResult.Found)
        assertEquals(1, lrclibCalls.get())
    }
}
