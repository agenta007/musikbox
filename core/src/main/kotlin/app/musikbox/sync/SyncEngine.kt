package app.musikbox.sync

import app.musikbox.sync.SyncCrypto.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What each app provides to the shared sync engine. Called from background threads. */
interface SyncHost {
    val deviceId: String
    val deviceName: String
    /** "android" or "linux". */
    val platform: String
    val appVersion: String
    /** Null until a key is known (from the server's `.musikbox` document, or derived at sign-in). */
    fun syncKey(): ByteArray?
    /** Persist the account-wide key found in the server document. */
    fun adoptSyncKey(key: ByteArray)
    fun shareDownloadList(): Boolean
    /** "host" or "host:port" entries the user added by hand. */
    fun manualPeers(): List<String>
    suspend fun localPrefs(): Map<String, PrefRecord>
    suspend fun applyPrefs(changes: Map<String, PrefRecord>)
    suspend fun nowPlaying(): NowPlayingInfo?
    suspend fun pausePlayback()
    fun log(message: String)
}

enum class PeerState(val label: String) {
    Unknown("Checking…"),
    Online("Online"),
    Synced("Synced"),
    Offline("Unreachable"),
    OtherAccount("Different account"),
    NotSetUp("Sync not set up there"),
    Unauthorized("Rejected — keys differ"),
    Error("Error"),
}

data class Peer(
    /** "host:port" — the address we talk to. */
    val key: String,
    val host: String,
    val port: Int,
    val id: String? = null,
    val name: String? = null,
    val platform: String? = null,
    val version: String? = null,
    val manual: Boolean = false,
    val discovered: Boolean = false,
    val state: PeerState = PeerState.Unknown,
    val message: String? = null,
    val lastSeenAt: Long = 0,
    val lastSyncAt: Long = 0,
    val nowPlaying: NowPlayingInfo? = null,
) {
    val displayName get() = name ?: host
}

data class ListenAddress(val ip: String, val iface: String)

data class SyncStatus(
    val running: Boolean = false,
    val port: Int = DEFAULT_SYNC_PORT,
    val addresses: List<ListenAddress> = emptyList(),
    val error: String? = null,
    val keyReady: Boolean = false,
    val lastSyncAt: Long = 0,
    val syncing: Boolean = false,
)

/**
 * Peer-to-peer sync between a user's musikbox apps on the LAN (or VPN):
 *  - serves a small signed JSON API on [port] (hello, sync, now-playing, pause);
 *  - finds peers with a UDP broadcast beacon every [BEACON_INTERVAL_MS], plus hand-added IPs;
 *  - syncs with every reachable peer of the same account periodically and soon after local changes.
 * Merging is last-writer-wins per field, so any device can sync with any other in any order.
 */
class SyncEngine(
    private val host: SyncHost,
    httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    val port: Int = DEFAULT_SYNC_PORT,
    /** UDP beacons; off in tests so real devices on the LAN can't interfere. */
    private val enableDiscovery: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = httpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val peerMap = ConcurrentHashMap<String, Peer>()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus(port = port))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var server: MiniHttpServer? = null
    /** The port actually in use (may differ from [port] if it was busy). */
    @Volatile var boundPort: Int = port
        private set
    @Volatile private var udp: DatagramSocket? = null
    private val jobs = mutableListOf<Job>()
    private var pendingSync: Job? = null
    private val syncMutex = Mutex()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (_status.value.running) return
        val srv = MiniHttpServer(port, ::handle, fallbacks = if (port == DEFAULT_SYNC_PORT) PORT_FALLBACKS else 0)
        val result = srv.start()
        server = srv.takeIf { result.isSuccess }
        result.onSuccess { p ->
            boundPort = p
            if (p != port) host.log("Port $port was busy — listening on $p instead")
        }
        result.onFailure { host.log("Sync server couldn't listen near port $port: ${it.message}") }
        _status.update {
            it.copy(
                port = boundPort,
                running = true,
                error = result.exceptionOrNull()?.let { e -> "No free port near $port (${e.message})" },
                addresses = listenAddresses(),
                keyReady = host.syncKey() != null,
            )
        }
        if (result.isSuccess) host.log("Sync listening on ${_status.value.addresses.joinToString { "${it.ip}:$boundPort" }.ifEmpty { "port $boundPort" }}")
        refreshManualPeers()
        if (enableDiscovery) startDiscovery()
        jobs += scope.launch(Dispatchers.IO) {
            delay(1_500)
            while (isActive) {
                _status.update { it.copy(addresses = listenAddresses(), keyReady = host.syncKey() != null) }
                syncAll()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pendingSync?.cancel()
        server?.stop()
        server = null
        runCatching { udp?.close() }
        udp = null
        _status.update { it.copy(running = false) }
    }

    /** Debounced: call after any local change worth propagating. */
    fun requestSync(delayMs: Long = 1_500) {
        if (!_status.value.running) return
        pendingSync?.cancel()
        pendingSync = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            syncAll()
        }
    }

    fun syncNow() = requestSync(0)

    /** Re-read hand-added peers (after the user edits the list). */
    fun refreshManualPeers() {
        val wanted = host.manualPeers().mapNotNull(::parseAddress).associateBy { "${it.first}:${it.second}" }
        peerMap.values.filter { it.manual && it.key !in wanted }.forEach { p ->
            if (p.discovered) peerMap[p.key] = p.copy(manual = false) else peerMap.remove(p.key)
        }
        wanted.forEach { (key, addr) ->
            peerMap.compute(key) { _, old -> old?.copy(manual = true) ?: Peer(key, addr.first, addr.second, manual = true) }
        }
        publish()
        requestSync(300)
    }

    // ---------------------------------------------------------------- client side

    suspend fun syncAll() = syncMutex.withLock {
        val targets = peerMap.values.toList()
        if (targets.isEmpty()) return@withLock
        _status.update { it.copy(syncing = true) }
        coroutineScope { targets.map { p -> async(Dispatchers.IO) { runCatching { syncPeer(p) } } }.awaitAll() }
        _status.update { it.copy(syncing = false, lastSyncAt = System.currentTimeMillis()) }
    }

    private suspend fun syncPeer(peer: Peer) {
        val hello = try {
            get<DeviceHello>(peer, "/musikbox/v1/hello", signed = false)
        } catch (e: Exception) {
            if (peer.state != PeerState.Offline) host.log("Peer ${peer.displayName} (${peer.key}) unreachable: ${e.message ?: e.javaClass.simpleName}")
            update(peer.key) { it.copy(state = PeerState.Offline, message = e.message, nowPlaying = null) }
            return
        }
        if (hello.id == host.deviceId) { // our own address
            if (!peer.manual) { peerMap.remove(peer.key); publish(); return }
            update(peer.key) { it.copy(id = hello.id, name = "${hello.name} (this device)", state = PeerState.Error, message = "This is this device") }
            return
        }
        dedupe(peer.key, hello.id)
        val base = { p: Peer ->
            p.copy(id = hello.id, name = hello.name, platform = hello.platform, version = hello.version, lastSeenAt = System.currentTimeMillis())
        }
        val key = host.syncKey()
        val myFp = key?.let(SyncCrypto::fingerprint)
        when {
            key == null -> { update(peer.key) { base(it).copy(state = PeerState.Online, message = "Set up sync on this device") }; return }
            hello.fingerprint == null -> { update(peer.key) { base(it).copy(state = PeerState.NotSetUp, message = null) }; return }
            hello.fingerprint != myFp -> { update(peer.key) { base(it).copy(state = PeerState.OtherAccount, message = null) }; return }
        }
        try {
            val share = host.shareDownloadList()
            val local = host.localPrefs()
            val response: SyncResponse = post(peer, "/musikbox/v1/sync", SyncRequest(myHello(), outgoing(local, share), share))
            val changes = mergeChanges(local, response.prefs, share && response.shareOffline)
            if (changes.isNotEmpty()) {
                host.applyPrefs(changes)
                host.log("Synced with ${hello.name}: ${changes.size} playlist change${if (changes.size == 1) "" else "s"} received")
            }
            val np = runCatching { get<NowPlayingInfo?>(peer, "/musikbox/v1/now-playing", signed = true) }.getOrNull()
            update(peer.key) { base(it).copy(state = PeerState.Synced, message = null, lastSyncAt = System.currentTimeMillis(), nowPlaying = np) }
        } catch (e: UnauthorizedException) {
            host.log("Peer ${hello.name} rejected our signature — sign in again on one of the devices")
            update(peer.key) { base(it).copy(state = PeerState.Unauthorized, message = e.message) }
        } catch (e: Exception) {
            host.log("Sync with ${hello.name} failed: ${e.message ?: e.javaClass.simpleName}")
            update(peer.key) { base(it).copy(state = PeerState.Error, message = e.message) }
        }
    }

    /** "Continue here": fetch the peer's queue and pause it there. */
    suspend fun takeOver(peer: Peer): NowPlayingInfo? = withContext(Dispatchers.IO) {
        val np = get<NowPlayingInfo?>(peer, "/musikbox/v1/now-playing", signed = true) ?: return@withContext null
        runCatching { post<Unit?>(peer, "/musikbox/v1/pause", null) }
            .onFailure { host.log("Couldn't pause ${peer.displayName}: ${it.message}") }
        host.log("Continuing playback from ${peer.displayName}: ${np.current?.title ?: "queue"}")
        np
    }

    private fun myHello() = DeviceHello(
        id = host.deviceId,
        name = host.deviceName,
        platform = host.platform,
        version = host.appVersion,
        port = boundPort,
        fingerprint = host.syncKey()?.let(SyncCrypto::fingerprint),
    )

    private class UnauthorizedException(msg: String) : IOException(msg)

    private inline fun <reified T> get(peer: Peer, path: String, signed: Boolean): T =
        execute(peer, "GET", path, ByteArray(0), signed) { json.decodeFromString<T>(it) }

    private inline fun <reified T> post(peer: Peer, path: String, body: Any?): T {
        val bytes = when (body) {
            null -> ByteArray(0)
            is SyncRequest -> json.encodeToString(SyncRequest.serializer(), body).toByteArray()
            else -> error("unsupported body")
        }
        return execute(peer, "POST", path, bytes, signed = true) { if (it.isBlank()) null as T else json.decodeFromString<T>(it) }
    }

    private fun <T> execute(peer: Peer, method: String, path: String, body: ByteArray, signed: Boolean, parse: (String) -> T): T {
        val builder = Request.Builder().url("http://${hostForUrl(peer.host)}:${peer.port}$path")
        if (signed) {
            val key = host.syncKey() ?: throw IOException("sync not set up")
            val time = System.currentTimeMillis()
            builder.header("X-MB-Device", host.deviceId)
                .header("X-MB-Time", "$time")
                .header("X-MB-Sig", SyncCrypto.sign(key, method, path, time, body))
        }
        if (method == "POST") builder.post(body.toRequestBody(JSON)) else builder.get()
        http.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw UnauthorizedException("HTTP ${r.code}")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return parse(text)
        }
    }

    // ---------------------------------------------------------------- server side

    private fun handle(req: HttpRequest): HttpResponse {
        if (req.method == "GET" && req.path == "/musikbox/v1/hello") {
            return HttpResponse(200, json.encodeToString(DeviceHello.serializer(), myHello()))
        }
        val key = host.syncKey() ?: return HttpResponse(403, """{"error":"sync not set up"}""")
        val time = req.headers["x-mb-time"]?.toLongOrNull()
        val sig = req.headers["x-mb-sig"]
        if (time == null || sig == null || !SyncCrypto.verify(key, req.method, req.path, time, req.body, sig)) {
            host.log("Rejected unsigned/invalid sync request from ${req.remoteHost}")
            return HttpResponse(401, """{"error":"bad signature"}""")
        }
        return runBlocking {
            when ("${req.method} ${req.path}") {
                "POST /musikbox/v1/sync" -> {
                    val r = json.decodeFromString(SyncRequest.serializer(), req.body.decodeToString())
                    val share = host.shareDownloadList()
                    val local = host.localPrefs()
                    val changes = mergeChanges(local, r.prefs, share && r.shareOffline)
                    if (changes.isNotEmpty()) {
                        host.applyPrefs(changes)
                        host.log("Synced with ${r.from.name}: ${changes.size} playlist change${if (changes.size == 1) "" else "s"} received")
                    }
                    noteInbound(r.from, req.remoteHost)
                    HttpResponse(200, json.encodeToString(SyncResponse.serializer(), SyncResponse(outgoing(local + changes, share), share)))
                }
                "GET /musikbox/v1/now-playing" -> {
                    val np = host.nowPlaying()
                    HttpResponse(200, np?.let { json.encodeToString(NowPlayingInfo.serializer(), it) } ?: "null")
                }
                "POST /musikbox/v1/pause" -> {
                    host.pausePlayback()
                    host.log("Paused by another device (continued there)")
                    HttpResponse(200, "null")
                }
                else -> HttpResponse(404, """{"error":"not found"}""")
            }
        }
    }

    /** A peer that synced with us is reachable at its source IP + advertised port. */
    private fun noteInbound(from: DeviceHello, remoteHost: String) {
        val existing = peerMap.values.firstOrNull { it.id == from.id }
        val now = System.currentTimeMillis()
        if (existing != null) {
            update(existing.key) { it.copy(name = from.name, platform = from.platform, state = PeerState.Synced, lastSeenAt = now, lastSyncAt = now) }
        } else if (remoteHost.isNotBlank() && !isLoopback(remoteHost)) {
            // Loopback sources come through port forwarding / emulator NAT and can't be dialled back.
            val key = "$remoteHost:${from.port}"
            peerMap[key] = Peer(key, remoteHost, from.port, from.id, from.name, from.platform, from.version, discovered = true, state = PeerState.Synced, lastSeenAt = now, lastSyncAt = now)
            publish()
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun startDiscovery() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            host.log("LAN discovery unavailable (UDP $DISCOVERY_PORT: ${e.message}); add peers by IP instead")
            return
        }
        udp = socket
        Thread({ receiveLoop(socket) }, "mb-sync-discovery").apply { isDaemon = true }.start()
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendBeacon(socket)
                expireDiscovered()
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    private fun sendBeacon(socket: DatagramSocket) {
        val beacon = Beacon(
            id = host.deviceId, name = host.deviceName, platform = host.platform, port = boundPort,
            fp = host.syncKey()?.let(SyncCrypto::fingerprint),
        )
        val bytes = json.encodeToString(Beacon.serializer(), beacon).toByteArray()
        val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
        targets.distinct().forEach { addr ->
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, addr, DISCOVERY_PORT)) }
        }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (e: Exception) { break }
            val beacon = runCatching { json.decodeFromString(Beacon.serializer(), String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            if (beacon.id == host.deviceId || beacon.mb != PROTOCOL_VERSION) continue
            val ip = packet.address.hostAddress ?: continue
            // Beacons relayed through emulator NAT / forwarding arrive from loopback and can't be dialled back.
            if (isLoopback(ip)) continue
            onBeacon(beacon, ip)
        }
    }

    private fun onBeacon(b: Beacon, ip: String) {
        val now = System.currentTimeMillis()
        val existing = peerMap.values.firstOrNull { it.id == b.id }
        if (existing != null) {
            update(existing.key) { it.copy(name = b.name, platform = b.platform, discovered = true, lastSeenAt = now) }
            return
        }
        val key = "$ip:${b.port}"
        val isNew = !peerMap.containsKey(key)
        peerMap.compute(key) { _, old ->
            (old ?: Peer(key, ip, b.port)).copy(id = b.id, name = b.name, platform = b.platform, discovered = true, lastSeenAt = now)
        }
        publish()
        if (isNew) {
            host.log("Found ${b.name} (${b.platform}) at $key")
            requestSync(500)
        }
    }

    /** Discovered (not hand-added) peers disappear after a few missed beacons and failed syncs. */
    private fun expireDiscovered() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        val gone = peerMap.values.filter { !it.manual && it.lastSeenAt < cutoff && it.state == PeerState.Offline }
        if (gone.isNotEmpty()) {
            gone.forEach { peerMap.remove(it.key) }
            publish()
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One device can be known under several addresses (hand-added 127.0.0.1, LAN IP from its
     * beacon, VPN IP). Once [id] is confirmed at [keepKey], drop the other auto-found entries.
     */
    private fun dedupe(keepKey: String, id: String) {
        val dupes = peerMap.values.filter { it.key != keepKey && it.id == id && !it.manual }
        if (dupes.isEmpty()) return
        dupes.forEach { peerMap.remove(it.key) }
        peerMap.computeIfPresent(keepKey) { _, p -> p.copy(discovered = p.discovered || dupes.any { it.discovered }) }
        publish()
    }

    private fun update(key: String, transform: (Peer) -> Peer) {
        peerMap.computeIfPresent(key) { _, p -> transform(p) }
        publish()
    }

    private fun publish() {
        _peers.value = peerMap.values.sortedWith(compareBy({ it.state != PeerState.Synced }, { it.displayName.lowercase() }))
    }

    companion object {
        const val SYNC_INTERVAL_MS = 45_000L
        const val BEACON_INTERVAL_MS = 10_000L
        const val PEER_TTL_MS = 3 * 60_000L
        private val JSON = "application/json".toMediaType()

        /** Accepts "192.168.1.5", "192.168.1.5:24533", "[fe80::1]:24533", "host.lan". */
        fun parseAddress(raw: String): Pair<String, Int>? {
            val s = raw.trim().removePrefix("http://").trimEnd('/')
            if (s.isEmpty()) return null
            if (s.startsWith("[")) {
                val end = s.indexOf(']').takeIf { it > 0 } ?: return null
                val h = s.substring(1, end)
                val p = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: DEFAULT_SYNC_PORT
                return h to p
            }
            val colon = s.count { it == ':' }
            return when {
                colon == 1 -> s.substringBefore(':') to (s.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
                colon > 1 -> s to DEFAULT_SYNC_PORT // bare IPv6
                else -> s to DEFAULT_SYNC_PORT
            }
        }

        private fun isLoopback(h: String) = h.startsWith("127.") || h == "::1" || h == "0:0:0:0:0:0:0:1"

        private fun hostForUrl(h: String) = if (h.contains(':')) "[$h]" else h

        fun listenAddresses(): List<ListenAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !isVirtual(it.name) }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ListenAddress(it.hostAddress ?: "", nif.name) }
                }
                .filter { it.ip.isNotBlank() }
                .sortedBy { a ->
                    when {
                        a.ip.startsWith("192.168.") -> 0
                        a.ip.startsWith("10.") -> 1
                        a.ip.startsWith("172.") -> 2
                        else -> 3
                    }
                }
        }.getOrDefault(emptyList())

        /** Container / VM bridges other devices can't reach (VPNs like wg0 / tun0 are kept). */
        private fun isVirtual(name: String) =
            listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "lxc", "lxd", "podman", "cni", "flannel").any { name.startsWith(it) }

        private fun broadcastAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
        }.getOrDefault(emptyList())

        /** Random device id (hex) for first launch. */
        fun newDeviceId(): String = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }.toHex()
    }
}
