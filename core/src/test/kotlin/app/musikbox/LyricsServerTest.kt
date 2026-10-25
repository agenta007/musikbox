package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import app.musikbox.lyrics.FileLyricsCache
import app.musikbox.lyrics.LyricLine
import app.musikbox.lyrics.LyricsQuery
import app.musikbox.lyrics.LyricsResult
import app.musikbox.lyrics.LyricsService
import app.musikbox.lyrics.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Lyrics from a real Navidrome: a synced .lrc sidecar and a plain .txt sidecar (see the test
 * environment script). Runs only with MB_TEST_SERVER=http://127.0.0.1:4599 (admin / testpass).
 */
class LyricsServerTest {
    private val server = System.getenv("MB_TEST_SERVER")
    private lateinit var api: SubsonicClient
    private lateinit var lyrics: LyricsService

    @Before fun setUp() {
        assumeTrue("set MB_TEST_SERVER to run", server != null)
        val creds = Credentials.create(server!!, "admin", "testpass")
        val http = OkHttpClient()
        api = SubsonicClient(http) { creds }
        lyrics = LyricsService(api, http, FileLyricsCache(Files.createTempDirectory("lyr").toFile()), { null }, "musikbox-test")
    }

    private suspend fun query(title: String): LyricsQuery {
        val s = api.search(title).first { it.title == title && it.artist == "Aurora Fields" }
        return LyricsQuery(s.id, s.title, s.artist, s.album, s.duration)
    }

    @Test fun syncedSidecarComesBackTimed() = runBlocking {
        val r = lyrics.load(query("Glass Harbor (First Light)")) as LyricsResult.Found
        assertEquals(LyricsSource.Server, r.lyrics.source)
        assertTrue(r.lyrics.synced)
        assertEquals(LyricLine(1_000, "Morning comes on silver wire"), r.lyrics.lines.first())
        assertEquals(LyricLine(25_000, "Bring me home"), r.lyrics.lines.last())
        assertEquals(3, r.lyrics.lineAt(12_000))
    }

    @Test fun plainSidecarComesBackUntimed() = runBlocking {
        val r = lyrics.load(query("Slow Signal (First Light)")) as LyricsResult.Found
        assertFalse(r.lyrics.synced)
        assertEquals("Slow signal in the static", r.lyrics.lines.first().text)
        assertTrue(r.lyrics.lines.all { it.startMs == null })
    }

    @Test fun songWithoutLyricsIsNone() = runBlocking {
        assertEquals(LyricsResult.None, lyrics.load(query("Neon Rain (First Light)")))
    }
}
