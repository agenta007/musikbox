package app.musikbox

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.Credentials
import app.musikbox.sync.CloudSync
import app.musikbox.sync.NowPlayingInfo
import app.musikbox.sync.PrefRecord
import app.musikbox.sync.SYNC_PLAYLIST_NAME
import app.musikbox.sync.SyncHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Two "devices" syncing through a real Navidrome. Runs only with
 * MB_TEST_SERVER=http://127.0.0.1:4599 (user admin / password testpass).
 */
class CloudSyncServerTest {
    private val server = System.getenv("MB_TEST_SERVER")
    private lateinit var api: SubsonicClient

    private class Device(override val deviceName: String) : SyncHost {
        override val deviceId = deviceName
        override val platform = "test"
        override val appVersion = "t"
        var key: ByteArray? = null
        var prefs = mapOf<String, PrefRecord>()
        var share = false
        override fun syncKey() = key
        override fun adoptSyncKey(key: ByteArray) { this.key = key }
        override fun shareDownloadList() = share
        override fun manualPeers() = emptyList<String>()
        override suspend fun localPrefs() = prefs
        override suspend fun applyPrefs(changes: Map<String, PrefRecord>) { prefs = prefs + changes }
        override suspend fun nowPlaying(): NowPlayingInfo? = null
        override suspend fun pausePlayback() {}
        override fun log(message: String) { println("[$deviceName] $message") }
    }

    @Before fun setUp() {
        assumeTrue("set MB_TEST_SERVER to run", server != null)
        val creds = Credentials.create(server!!, "admin", "testpass")
        api = SubsonicClient(OkHttpClient()) { creds }
        runBlocking { api.getPlaylists().filter { it.name == SYNC_PLAYLIST_NAME }.forEach { api.deletePlaylist(it.id) } }
    }

    private fun cloud(d: Device) = CloudSync(api, d, CoroutineScope(SupervisorJob() + Dispatchers.IO), { "admin" })

    @Test fun devicesConvergeThroughTheServer() = runBlocking {
        val phone = Device("phone").apply { prefs = mapOf("plA" to PrefRecord(favorite = true, favoriteAt = 1000)) }
        val desktop = Device("desktop").apply {
            prefs = mapOf("plB" to PrefRecord(favorite = true, favoriteAt = 2000), "plA" to PrefRecord(lastPlayedAt = 5000))
        }
        cloud(phone).syncNow().getOrThrow()          // creates the doc + key
        cloud(desktop).syncNow().getOrThrow()        // pulls plA fav, pushes plB + plA played
        cloud(phone).syncNow().getOrThrow()          // pulls plB + played
        assertEquals(desktop.prefs, phone.prefs)
        assertTrue(phone.prefs["plB"]!!.favorite)
        assertEquals(5000L, phone.prefs["plA"]!!.lastPlayedAt)
        assertNotNull(phone.key)
        assertArrayEquals("both devices share the server's key", phone.key, desktop.key)

        // Un-saving later wins over the older favourite everywhere.
        desktop.prefs = desktop.prefs + ("plB" to PrefRecord(favorite = false, favoriteAt = 3000))
        cloud(desktop).syncNow().getOrThrow()
        cloud(phone).syncNow().getOrThrow()
        assertFalse(phone.prefs["plB"]!!.favorite)

        val lists = api.getPlaylists().filter { it.name == SYNC_PLAYLIST_NAME }
        assertEquals(1, lists.size)
        assertEquals(0, lists.single().songCount)
        assertFalse("must stay private", lists.single().public)
    }

    @Test fun simultaneousFirstSyncsLeaveOnePlaylistAndLoseNothing() = runBlocking {
        val devices = (1..4).map { i -> Device("d$i").apply { prefs = mapOf("p$i" to PrefRecord(favorite = true, favoriteAt = i * 10L)) } }
        devices.map { d -> async(Dispatchers.IO) { cloud(d).syncNow() } }.awaitAll()
        // Second round heals any doc overwritten during the race.
        devices.forEach { cloud(it).syncNow().getOrThrow() }
        devices.forEach { cloud(it).syncNow().getOrThrow() }
        val expected = setOf("p1", "p2", "p3", "p4")
        devices.forEach { assertEquals(it.deviceName, expected, it.prefs.filterValues { r -> r.favorite }.keys) }
        assertEquals(1, api.getPlaylists().count { it.name == SYNC_PLAYLIST_NAME })
        assertEquals(1, devices.map { it.key!!.toList() }.distinct().size)
    }

    @Test fun unsharedDownloadListIsNotClobbered() = runBlocking {
        val sharer = Device("sharer").apply { share = true; prefs = mapOf("p" to PrefRecord(offline = true, offlineAt = 100)) }
        val private = Device("private").apply { prefs = mapOf("p" to PrefRecord(offline = false, offlineAt = 999)) }
        cloud(sharer).syncNow().getOrThrow()
        cloud(private).syncNow().getOrThrow()   // doesn't share: must neither adopt nor overwrite
        assertFalse(private.prefs["p"]!!.offline)
        val other = Device("other-sharer").apply { share = true }
        cloud(other).syncNow().getOrThrow()
        assertTrue("sharer's download list survives a non-sharing device", other.prefs["p"]!!.offline)
    }
}
