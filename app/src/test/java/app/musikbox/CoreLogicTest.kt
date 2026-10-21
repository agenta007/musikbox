package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.remote.SubsonicEnvelope
import app.musikbox.data.settings.Credentials
import app.musikbox.data.toEntity
import app.musikbox.playback.OfflineManager
import app.musikbox.playback.cacheKeyFor
import app.musikbox.ui.components.formatBytes
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.formatTrackTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class CoreLogicTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun tokenIsSaltedMd5OfPassword() {
        val c = Credentials.create("https://x", "me", "sesame")
        assertEquals(16, c.salt.length)
        assertEquals(md5("sesame" + c.salt), c.token)
        assertNotEquals(c.salt, Credentials.create("https://x", "me", "sesame").salt)
    }

    @Test fun serverUrlNormalization() {
        assertEquals("https://music.local:4533", SubsonicClient.normalizeServerUrl(" music.local:4533/ "))
        assertEquals("http://10.0.0.2/nd", SubsonicClient.normalizeServerUrl("http://10.0.0.2/nd/"))
    }

    @Test fun streamUrlsAreStableAndCarryQuality() {
        val creds = Credentials("https://h/nd", "me", "abc", "tok")
        val api = SubsonicClient(okhttp3.OkHttpClient()) { creds }
        val raw = api.streamUrl("s1", 0)!!
        assertTrue(raw.startsWith("https://h/nd/rest/stream.view?"))
        assertTrue("format=raw" in raw)
        assertTrue("maxBitRate=192" in api.streamUrl("s1", 192)!!)
        assertEquals(raw, api.streamUrl("s1", 0))
        assertNull(api.coverArtUrl(null))
    }

    @Test fun cacheKeysSeparateQualities() {
        assertEquals("song:a:raw", cacheKeyFor("a", 0))
        assertEquals("song:a:320", cacheKeyFor("a", 320))
    }

    @Test fun parsesNavidromePlaylistAndNumericIds() {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{
              "id":42,"name":"Mix","songCount":2,"duration":300,"changed":"2026-01-01T00:00:00Z","coverArt":"pl-42",
              "entry":[
                {"id":"s1","title":"One","artist":"A","duration":120,"starred":"2025-01-01T00:00:00Z","coverArt":7},
                {"id":2,"title":"Two","duration":180,"replayGain":{"trackGain":1.0}}
              ]}}}
        """.trimIndent()
        val pl = json.decodeFromString<SubsonicEnvelope>(body).response.playlist!!
        assertEquals("42", pl.id)
        val songs = pl.entry.map { it.toEntity() }
        assertEquals(listOf("s1", "2"), songs.map { it.id })
        assertTrue(songs[0].starred)
        assertFalse(songs[1].starred)
        assertEquals("7", songs[0].coverArt)
        assertEquals("2026-01-01T00:00:00Z", pl.toEntity(null).changed)
    }

    @Test fun parsesFailure() {
        val r = json.decodeFromString<SubsonicEnvelope>(
            """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""",
        ).response
        assertEquals("failed", r.status)
        assertEquals(40, r.error?.code)
    }

    @Test fun downloadErrorsAreReadableAndHideTheUrl() {
        val wrapped = java.io.IOException(
            java.util.concurrent.ExecutionException(java.io.IOException("unexpected end of stream on http://h/rest/stream.view?t=secret")),
        )
        assertEquals("Server closed the connection (down or restarting?)", OfflineManager.describe(wrapped))
        assertEquals("Server not reachable (DNS)", OfflineManager.describe(java.io.IOException(java.net.UnknownHostException("h"))))
        val other = OfflineManager.describe(java.io.IOException("boom on http://h/rest?t=secret"))
        assertEquals("boom", other)
        assertFalse("secret" in other)
    }

    @Test fun formatting() {
        assertEquals("3:05", formatTrackTime(185_000))
        assertEquals("1:01:01", formatTrackTime(3_661_000))
        assertEquals("2 h 5 min", formatLongDuration(7_500))
        assertEquals("1.5 MB", formatBytes(1_572_864))
    }
}
