package app.musikbox

import app.musikbox.data.remote.AlbumDto
import app.musikbox.data.remote.SongDto
import app.musikbox.data.remote.Starred2Dto
import app.musikbox.discover.Candidate
import app.musikbox.discover.Source
import app.musikbox.discover.buildProfile
import app.musikbox.discover.rankMix
import app.musikbox.sync.NowPlayingInfo
import app.musikbox.sync.PeerState
import app.musikbox.sync.PrefRecord
import app.musikbox.sync.RemoteSong
import app.musikbox.sync.SyncCrypto
import app.musikbox.sync.SyncEngine
import app.musikbox.sync.SyncHost
import app.musikbox.sync.mergeChanges
import app.musikbox.sync.mergeRecord
import app.musikbox.sync.outgoing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.random.Random

class SyncAndDiscoverTest {

    // ---------------------------------------------------------------- merge

    @Test fun favouriteIsLastWriterWins() {
        val local = PrefRecord(favorite = true, favoriteAt = 100)
        val remote = PrefRecord(favorite = false, favoriteAt = 200)
        assertFalse(mergeRecord(local, remote, includeOffline = false).favorite)
        assertTrue(mergeRecord(remote, local, includeOffline = false).let { !it.favorite && it.favoriteAt == 200L })
    }

    @Test fun lastPlayedOnlyMovesForward() {
        val m = mergeRecord(PrefRecord(lastPlayedAt = 500), PrefRecord(lastPlayedAt = 300), false)
        assertEquals(500L, m.lastPlayedAt)
    }

    @Test fun downloadListOnlyMergesWhenShared() {
        val local = PrefRecord(offline = false, offlineAt = 10)
        val remote = PrefRecord(offline = true, offlineAt = 99)
        assertFalse(mergeRecord(local, remote, includeOffline = false).offline)
        assertTrue(mergeRecord(local, remote, includeOffline = true).offline)
        val sent = outgoing(mapOf("p" to PrefRecord(offline = true, offlineAt = 5)), shareOffline = false)
        assertEquals(PrefRecord(), sent["p"])
    }

    @Test fun mergeChangesReportsOnlyDifferences() {
        val local = mapOf("a" to PrefRecord(favorite = true, favoriteAt = 5), "b" to PrefRecord(lastPlayedAt = 9))
        val remote = mapOf("a" to PrefRecord(favorite = true, favoriteAt = 5), "b" to PrefRecord(lastPlayedAt = 20), "c" to PrefRecord(favorite = true, favoriteAt = 1))
        val changes = mergeChanges(local, remote, false)
        assertEquals(setOf("b", "c"), changes.keys)
    }

    // ---------------------------------------------------------------- crypto

    @Test fun keysMatchPerAccountAndSignaturesVerify() {
        val k1 = SyncCrypto.deriveKey("Neo", "pw")
        val k2 = SyncCrypto.deriveKey(" neo ", "pw")
        val other = SyncCrypto.deriveKey("neo", "different")
        assertEquals(SyncCrypto.fingerprint(k1), SyncCrypto.fingerprint(k2))
        assertFalse(SyncCrypto.fingerprint(k1) == SyncCrypto.fingerprint(other))
        val now = System.currentTimeMillis()
        val sig = SyncCrypto.sign(k1, "POST", "/x", now, "body".toByteArray())
        assertTrue(SyncCrypto.verify(k2, "POST", "/x", now, "body".toByteArray(), sig))
        assertFalse(SyncCrypto.verify(k1, "POST", "/x", now, "tampered".toByteArray(), sig))
        assertFalse(SyncCrypto.verify(other, "POST", "/x", now, "body".toByteArray(), sig))
        assertFalse(SyncCrypto.verify(k1, "POST", "/x", now - 10 * 60_000, "body".toByteArray(), SyncCrypto.sign(k1, "POST", "/x", now - 10 * 60_000, "body".toByteArray())))
    }

    @Test fun parsesPeerAddresses() {
        assertEquals("192.168.1.5" to 24533, SyncEngine.parseAddress("192.168.1.5"))
        assertEquals("10.8.0.3" to 5000, SyncEngine.parseAddress(" 10.8.0.3:5000 "))
        assertEquals("fe80::1" to 24533, SyncEngine.parseAddress("[fe80::1]"))
        assertEquals(null, SyncEngine.parseAddress("host:99999"))
    }

    // ---------------------------------------------------------------- end-to-end over real sockets

    private class FakeHost(override val deviceId: String, override val deviceName: String, private val key: ByteArray?, private val peers: List<String>) : SyncHost {
        override val platform = "test"
        override val appVersion = "t"
        var prefs = mapOf<String, PrefRecord>()
        var paused = false
        var np: NowPlayingInfo? = null
        val logs = mutableListOf<String>()
        var share = false
        override fun syncKey() = key
        override fun adoptSyncKey(key: ByteArray) = Unit
        override fun shareDownloadList() = share
        override fun manualPeers() = peers
        override suspend fun localPrefs() = prefs
        override suspend fun applyPrefs(changes: Map<String, PrefRecord>) { prefs = prefs + changes }
        override suspend fun nowPlaying() = np
        override suspend fun pausePlayback() { paused = true }
        override fun log(message: String) { synchronized(logs) { logs += message } }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun twoEnginesConvergeAndHandOver() = runBlocking {
        val key = SyncCrypto.deriveKey("neo", "secret")
        val pA = freePort(); val pB = freePort()
        val a = FakeHost("aaaa", "Phone", key, listOf("127.0.0.1:$pB"))
        val b = FakeHost("bbbb", "Desktop", key, emptyList())
        a.prefs = mapOf("pl1" to PrefRecord(favorite = true, favoriteAt = 1000), "pl2" to PrefRecord(lastPlayedAt = 50))
        b.prefs = mapOf("pl2" to PrefRecord(lastPlayedAt = 900), "pl3" to PrefRecord(favorite = true, favoriteAt = 10))
        b.np = NowPlayingInfo(true, 42_000, 1, listOf(RemoteSong("s1", "One"), RemoteSong("s2", "Two")), "pl2")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ea = SyncEngine(a, OkHttpClient(), scope, pA, enableDiscovery = false)
        val eb = SyncEngine(b, OkHttpClient(), scope, pB, enableDiscovery = false)
        try {
            eb.start(); ea.start()
            ea.syncAll()
            val expected = setOf("pl1", "pl2", "pl3")
            assertEquals(expected, a.prefs.keys)
            assertEquals(expected, b.prefs.keys)
            assertEquals(900L, a.prefs["pl2"]!!.lastPlayedAt)
            assertTrue(b.prefs["pl1"]!!.favorite)
            val peer = ea.peers.value.single()
            assertEquals(PeerState.Synced, peer.state)
            assertEquals("Desktop", peer.name)
            assertEquals("Two", peer.nowPlaying?.current?.title)
            // A reached B over loopback (port forwarding / NAT): B must not add an undiallable 127.x peer.
            assertTrue(eb.peers.value.none { it.host.startsWith("127.") })
            // Handoff pauses the other side.
            val np = ea.takeOver(peer)
            assertNotNull(np)
            assertEquals(42_000L, np!!.positionMs)
            assertTrue(b.paused)
        } finally {
            ea.stop(); eb.stop(); scope.cancel()
        }
    }

    @Test fun busyPortFallsBackToNextFree() {
        // Occupy the default port (if something — e.g. a running musikbox — already does, even better).
        val blocker = ServerSocket()
        blocker.reuseAddress = true
        runCatching { blocker.bind(java.net.InetSocketAddress(app.musikbox.sync.DEFAULT_SYNC_PORT)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = SyncEngine(FakeHost("cccc", "X", null, emptyList()), OkHttpClient(), scope, enableDiscovery = false)
        try {
            e.start()
            assertTrue(e.boundPort in app.musikbox.sync.DEFAULT_SYNC_PORT + 1..app.musikbox.sync.DEFAULT_SYNC_PORT + app.musikbox.sync.PORT_FALLBACKS)
            assertEquals(e.boundPort, e.status.value.port)
            assertEquals(null, e.status.value.error)
        } finally {
            e.stop(); blocker.close(); scope.cancel()
        }
    }

    @Test fun otherAccountIsNotSynced() = runBlocking {
        val pB = freePort()
        val a = FakeHost("aaaa", "Phone", SyncCrypto.deriveKey("neo", "x"), listOf("127.0.0.1:$pB"))
        val b = FakeHost("bbbb", "Laptop", SyncCrypto.deriveKey("trinity", "y"), emptyList())
        b.prefs = mapOf("p" to PrefRecord(favorite = true, favoriteAt = 1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ea = SyncEngine(a, OkHttpClient(), scope, freePort(), enableDiscovery = false)
        val eb = SyncEngine(b, OkHttpClient(), scope, pB, enableDiscovery = false)
        try {
            eb.start(); ea.start(); ea.syncAll()
            assertEquals(PeerState.OtherAccount, ea.peers.value.single().state)
            assertTrue(a.prefs.isEmpty())
        } finally {
            ea.stop(); eb.stop(); scope.cancel()
        }
    }

    // ---------------------------------------------------------------- discover

    @Test fun mixNeverPadsWithPlayedSongs() {
        val profile = buildProfile(emptyList(), emptyList(), Starred2Dto())
        val candidates = (1..3).map { Candidate(song("u$it", "U", "u", "E"), Source.Genre) } +
            (1..10).map { Candidate(song("p$it", "P$it", "p$it", "E", plays = 3), Source.Similar) }
        val mix = rankMix(candidates, profile, Random(2), 10)
        assertEquals(3, mix.size) // relaxed cap lets the 3 unheard in; played ones never
        assertTrue(mix.all { it.id.startsWith("u") })
    }

    private fun song(id: String, artist: String, album: String, genre: String, plays: Long = 0) =
        SongDto(id = id, title = id, artist = artist, artistId = artist, album = album, albumId = album, genre = genre, playCount = plays)

    @Test fun profileFavoursWhatYouPlayAndStar() {
        val frequent = listOf(AlbumDto("a1", artist = "A", artistId = "A", genre = "Electronic", playCount = 40))
        val starred = Starred2Dto(song = listOf(song("x", "B", "b1", "Ambient")))
        val p = buildProfile(frequent, emptyList(), starred)
        assertEquals("A", p.topArtists.first().first)
        assertEquals("Electronic", p.topGenres.first())
        assertTrue(p.genres.containsKey("Ambient"))
    }

    @Test fun mixPrefersUnheardAndStaysDiverse() {
        val profile = buildProfile(listOf(AlbumDto("a1", artist = "A", artistId = "A", genre = "E", playCount = 30)), emptyList(), Starred2Dto())
        val candidates = (1..10).map { Candidate(song("a$it", "A", "alb${it % 2}", "E"), Source.Genre) } +
            (1..5).map { Candidate(song("heard$it", "C", "c$it", "E", plays = 20), Source.Genre) } +
            (1..4).map { Candidate(song("b$it", "B", "b$it", "X"), Source.Similar) } +
            (1..3).map { Candidate(song("d$it", "D", "d$it", "E"), Source.Genre) }
        val mix = rankMix(candidates, profile, Random(1), 6)
        assertEquals(6, mix.size)
        // ≤ 2 per artist, the heavily played artist C loses to unheard ones, no artist back to back.
        assertTrue(mix.groupBy { it.artistId }.values.all { it.size <= 2 })
        assertTrue(mix.all { it.playCount == 0L }) // "songs you haven't heard yet" must be literally true
        assertTrue(mix.zipWithNext().none { (x, y) -> x.artistId == y.artistId })
        assertTrue(mix.take(2).any { it.artistId == "A" }) // strongest taste affinity ranks early
    }
}
