package app.musikbox

import app.musikbox.sync.NowPlayingInfo
import app.musikbox.sync.PeerState
import app.musikbox.sync.PlaybackGroup
import app.musikbox.sync.PrefRecord
import app.musikbox.sync.RemoteCommand
import app.musikbox.sync.RemoteSender
import app.musikbox.sync.RemoteSong
import app.musikbox.sync.SyncCrypto
import app.musikbox.sync.SyncEngine
import app.musikbox.sync.SyncHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class RemoteControlTest {
    private class Host(override val deviceId: String, override val deviceName: String, private val key: ByteArray?, private val peers: List<String>) : SyncHost {
        override val platform = "test"
        override val appVersion = "t"
        var np: NowPlayingInfo? = null
        val received = mutableListOf<RemoteCommand>()
        override fun syncKey() = key
        override fun adoptSyncKey(key: ByteArray) = Unit
        override fun shareDownloadList() = false
        override fun manualPeers() = peers
        override suspend fun localPrefs() = emptyMap<String, PrefRecord>()
        override suspend fun applyPrefs(changes: Map<String, PrefRecord>) = Unit
        override suspend fun nowPlaying() = np
        override suspend fun pausePlayback() = Unit
        override suspend fun remoteCommand(command: RemoteCommand) {
            synchronized(received) { received += command }
            // Act like a player, so now-playing reflects the command.
            when (command.action) {
                RemoteCommand.LOAD -> np = command.queue
                RemoteCommand.PAUSE -> np = np?.copy(playing = false)
                RemoteCommand.PLAY -> np = np?.copy(playing = true)
            }
        }
        override fun log(message: String) = Unit
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private val queue = NowPlayingInfo(true, 30_000, 1, listOf(RemoteSong("s1", "One"), RemoteSong("s2", "Two")), "pl")

    private fun pair(keyB: ByteArray?, block: suspend (SyncEngine, Host) -> Unit) = runBlocking {
        val key = SyncCrypto.deriveKey("neo", "secret")
        val pB = freePort()
        val a = Host("aaaa", "Phone", key, listOf("127.0.0.1:$pB"))
        val b = Host("bbbb", "Desktop", keyB ?: key, emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ea = SyncEngine(a, OkHttpClient(), scope, freePort(), enableDiscovery = false)
        val eb = SyncEngine(b, OkHttpClient(), scope, pB, enableDiscovery = false)
        try {
            eb.start(); ea.start(); ea.syncAll()
            block(ea, b)
        } finally {
            ea.stop(); eb.stop(); scope.cancel()
        }
    }

    @Test fun aDeviceCanStartAndStopAnother() = pair(null) { ea, b ->
        val peer = ea.peers.value.single()
        assertEquals(PeerState.Synced, peer.state)
        assertTrue(ea.control(peer, RemoteCommand(RemoteCommand.LOAD, queue = queue)).isSuccess)
        assertEquals("Two", ea.peers.value.single().nowPlaying?.current?.title)  // refreshed right after
        assertTrue(ea.control(peer, RemoteCommand(RemoteCommand.PAUSE)).isSuccess)
        assertFalse(ea.peers.value.single().nowPlaying!!.playing)
        assertEquals(listOf("load", "pause"), b.received.map { it.action })
        assertEquals("Phone", b.received.first().from)
    }

    @Test fun theGroupSenderFindsDevicesById() = pair(null) { ea, b ->
        assertTrue(ea.sender.send("bbbb", RemoteCommand(RemoteCommand.NEXT)))
        assertFalse(ea.sender.send("nobody", RemoteCommand(RemoteCommand.NEXT)))
        assertEquals(listOf("next"), b.received.map { it.action })
    }

    @Test fun anotherAccountsDeviceCantBeControlled() = pair(SyncCrypto.deriveKey("someone", "else")) { ea, b ->
        val peer = ea.peers.value.single()
        assertEquals(PeerState.OtherAccount, peer.state)
        assertFalse(ea.sender.send("bbbb", RemoteCommand(RemoteCommand.PAUSE)))
        // Even sent directly, the signature doesn't verify there.
        assertTrue(ea.control(peer, RemoteCommand(RemoteCommand.PAUSE)).isFailure)
        assertTrue(b.received.isEmpty())
    }

    // ---- what followers are told

    private fun np(index: Int = 0, playing: Boolean = true, pos: Long = 0, ids: List<String> = listOf("a", "b", "c")) =
        NowPlayingInfo(playing, pos, index, ids.map { RemoteSong(it, it) })

    @Test fun aNewTrackOrQueueLoadsIt() {
        assertEquals(listOf("load"), PlaybackGroup.diff(np(0), 0, np(1), 1000).map { it.action })
        assertEquals(listOf("load"), PlaybackGroup.diff(np(0), 0, np(0, ids = listOf("x")), 1000).map { it.action })
        assertEquals(listOf("load"), PlaybackGroup.diff(null, 0, np(0), 0).map { it.action })
    }

    @Test fun normalPlaybackSendsNothing() {
        assertEquals(emptyList<RemoteCommand>(), PlaybackGroup.diff(np(pos = 10_000), 0, np(pos = 11_000), 1_000))
        assertEquals(emptyList<RemoteCommand>(), PlaybackGroup.diff(np(playing = false, pos = 5_000), 0, np(playing = false, pos = 5_000), 60_000))
    }

    @Test fun pauseResumeAndSeekGoThrough() {
        assertEquals(listOf("pause"), PlaybackGroup.diff(np(pos = 10_000), 0, np(playing = false, pos = 10_500), 500).map { it.action })
        assertEquals(listOf("play"), PlaybackGroup.diff(np(playing = false, pos = 10_000), 0, np(pos = 10_000), 9_000).map { it.action })
        val seek = PlaybackGroup.diff(np(pos = 10_000), 0, np(pos = 90_000), 1_000)
        assertEquals(listOf("seek"), seek.map { it.action })
        assertEquals(90_000L, seek.single().positionMs)
        assertEquals(listOf("pause"), PlaybackGroup.diff(np(), 0, null, 1).map { it.action })
    }

    @Test fun onlyLinkedDevicesFollow() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        var now = 0L
        val group = PlaybackGroup(RemoteSender { id, c -> synchronized(sent) { sent += id to c.action }; true },
            CoroutineScope(Dispatchers.Default), clock = { now })
        group.onLocal(np(0))
        group.link("desk", np(0))
        delay(200)
        now = 1_000; group.onLocal(np(0, pos = 1_000))   // nothing new
        now = 2_000; group.onLocal(np(0, playing = false, pos = 2_000))
        now = 3_000; group.onLocal(np(1))
        delay(300)
        assertEquals(listOf("desk" to "load", "desk" to "pause", "desk" to "load"), sent)
        group.unlink("desk")
        now = 4_000; group.onLocal(np(2))
        delay(200)
        assertEquals(3, sent.size)
        assertFalse(group.isLinked("desk"))
    }
}
