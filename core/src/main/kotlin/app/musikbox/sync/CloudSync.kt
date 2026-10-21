package app.musikbox.sync

import app.musikbox.data.remote.PlaylistDto
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.sync.SyncCrypto.hexToBytes
import app.musikbox.sync.SyncCrypto.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Name of the private playlist whose comment holds the account's musikbox state. Hidden in the apps. */
const val SYNC_PLAYLIST_NAME = ".musikbox"

/** The JSON stored in the `.musikbox` playlist's comment. */
@Serializable
data class CloudDoc(
    val v: Int = 1,
    /** Account-wide key for device-to-device requests (hex). */
    val key: String? = null,
    val prefs: Map<String, PrefRecord> = emptyMap(),
    val updatedAt: Long = 0,
    val updatedBy: String? = null,
)

data class CloudStatus(
    val lastSyncAt: Long = 0,
    val syncing: Boolean = false,
    val error: String? = null,
)

/**
 * Syncs saved playlists, recently played and (optionally) the download list through the
 * Navidrome server, so devices stay in step anywhere, any time — no LAN, no pairing.
 *
 * Subsonic can't store per-playlist favourites, so the state lives as JSON in the comment of
 * one private, empty playlist named [SYNC_PLAYLIST_NAME]. Each pass is: read doc → merge into
 * local (per-field last-writer-wins) → merge local into doc → write only if it changed.
 * Concurrent writers can drop each other's newest change from the doc, but the writer still
 * has it locally and re-publishes it on its next pass, so every device converges.
 */
class CloudSync(
    private val api: SubsonicClient,
    private val host: SyncHost,
    private val scope: CoroutineScope,
    /** Username of the signed-in account; only its own `.musikbox` playlist is used. */
    private val username: () -> String?,
    private val onKeyChanged: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val mutex = Mutex()
    private val _status = MutableStateFlow(CloudStatus())
    val status: StateFlow<CloudStatus> = _status.asStateFlow()
    private var loop: Job? = null
    private var pending: Job? = null

    fun start(intervalMs: Long = 2 * 60_000L) {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                syncNow()
                delay(intervalMs)
            }
        }
    }

    fun stop() { loop?.cancel(); pending?.cancel() }

    /** Debounced: call after a local change. */
    fun requestSync(delayMs: Long = 2_500) {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) { delay(delayMs); syncNow() }
    }

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        val me = username() ?: return Result.failure(IllegalStateException("not signed in"))
        _status.value = _status.value.copy(syncing = true)
        val result = runCatching {
            val all = api.getPlaylists()
            val mine = all.filter { isSyncPlaylist(it, me) }.sortedBy { it.created ?: "" }
            val canonical = mine.firstOrNull()
            val onServer = canonical?.comment?.let { parse(it) }
            var doc = onServer ?: CloudDoc()
            // Two devices may have created the playlist at the same time: fold extras in and delete them.
            mine.drop(1).forEach { extra ->
                extra.comment?.let { parse(it) }?.let { other -> doc = mergeDocs(doc, other) }
                runCatching { api.deletePlaylist(extra.id) }
                host.log("Merged a duplicate $SYNC_PLAYLIST_NAME playlist")
            }

            // Key: the server's wins; otherwise publish ours (e.g. one derived at sign-in) or a new one.
            val local = host.syncKey()
            val key = doc.key ?: local?.toHex() ?: newKey()
            if (doc.key == null) doc = doc.copy(key = key)
            if (local == null || local.toHex() != key) {
                host.adoptSyncKey(key.hexToBytes())
                host.log("Device-sync key taken from the server")
                onKeyChanged()
            }

            // Pull.
            val share = host.shareDownloadList()
            val localPrefs = host.localPrefs()
            val changes = mergeChanges(localPrefs, doc.prefs, includeOffline = share)
            if (changes.isNotEmpty()) {
                host.applyPrefs(changes)
                host.log("Server sync: ${changes.size} playlist change${if (changes.size == 1) "" else "s"} received")
            }
            // Push: merge our (outgoing) view into the doc; unshared download-list fields stay as others left them.
            val merged = mergeInto(doc.prefs, outgoing(localPrefs + changes, share))
            val next = doc.copy(prefs = merged)
            // Write only when the content (ignoring who/when) differs from what the server holds.
            if (onServer == null || mine.size > 1 || next.copy(updatedAt = 0, updatedBy = null) != onServer.copy(updatedAt = 0, updatedBy = null)) {
                val text = json.encodeToString(
                    CloudDoc.serializer(),
                    next.copy(updatedAt = System.currentTimeMillis(), updatedBy = host.deviceName),
                )
                val id = canonical?.id ?: createPlaylist(me)
                api.updatePlaylist(id, comment = text, public = false)
            }
        }
        _status.value = CloudStatus(
            lastSyncAt = if (result.isSuccess) System.currentTimeMillis() else _status.value.lastSyncAt,
            syncing = false,
            error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
        )
        result.onFailure { host.log("Server sync failed: ${it.message ?: it.javaClass.simpleName}") }
        result
    }

    private suspend fun createPlaylist(me: String): String {
        api.createPlaylist(SYNC_PLAYLIST_NAME, emptyList())
        host.log("Created the private $SYNC_PLAYLIST_NAME playlist for cross-device sync")
        return api.getPlaylists().filter { isSyncPlaylist(it, me) }.minByOrNull { it.created ?: "" }?.id
            ?: error("couldn't find the new $SYNC_PLAYLIST_NAME playlist")
    }

    private fun parse(text: String): CloudDoc? = runCatching { json.decodeFromString(CloudDoc.serializer(), text) }.getOrNull()

    private fun newKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()

    companion object {
        fun isSyncPlaylist(p: PlaylistDto, me: String?): Boolean =
            p.name == SYNC_PLAYLIST_NAME && (me == null || p.owner == null || p.owner.equals(me, ignoreCase = true))

        /** Every field takes its newest value; the doc never loses download-list data it can't see. */
        fun mergeInto(doc: Map<String, PrefRecord>, incoming: Map<String, PrefRecord>): Map<String, PrefRecord> =
            doc + incoming.mapValues { (id, r) -> mergeRecord(doc[id], r, includeOffline = true) }

        fun mergeDocs(a: CloudDoc, b: CloudDoc): CloudDoc = a.copy(
            key = a.key ?: b.key,
            prefs = mergeInto(a.prefs, b.prefs),
        )
    }
}
