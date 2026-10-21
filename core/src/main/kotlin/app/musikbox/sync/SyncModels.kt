package app.musikbox.sync

import kotlinx.serialization.Serializable

/**
 * Per-playlist state that Subsonic can't store, synced between a user's musikbox devices.
 * Each field carries the time it last changed, so merges are last-writer-wins per field.
 */
@Serializable
data class PrefRecord(
    val favorite: Boolean = false,
    /** When [favorite] last changed (ms). */
    val favoriteAt: Long = 0,
    /** Plays only move forward: merged with max(). */
    val lastPlayedAt: Long = 0,
    /** In the download list. Only exchanged when both devices share their download list. */
    val offline: Boolean = false,
    val offlineAt: Long = 0,
)

@Serializable
data class DeviceHello(
    val id: String,
    val name: String,
    val platform: String,
    val version: String,
    val port: Int,
    /** Hash of the sync key: equal on devices of the same account; null = sync not set up. */
    val fingerprint: String? = null,
    val proto: Int = PROTOCOL_VERSION,
)

@Serializable
data class SyncRequest(
    val from: DeviceHello,
    val prefs: Map<String, PrefRecord>,
    val shareOffline: Boolean,
)

@Serializable
data class SyncResponse(
    val prefs: Map<String, PrefRecord>,
    val shareOffline: Boolean,
)

@Serializable
data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val durationSec: Int = 0,
)

/** What a device is playing, with enough of the queue for another device to continue it. */
@Serializable
data class NowPlayingInfo(
    val playing: Boolean,
    val positionMs: Long,
    val index: Int,
    val queue: List<RemoteSong>,
    val playlistId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val current: RemoteSong? get() = queue.getOrNull(index)
}

/** UDP discovery beacon. */
@Serializable
data class Beacon(
    val mb: Int = PROTOCOL_VERSION,
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val fp: String? = null,
)

const val PROTOCOL_VERSION = 1
/**
 * Below the Linux/Android ephemeral range (32768–60999): a fixed port inside it can collide
 * with the local port of any outgoing connection. 24533 sits next to Navidrome's 4533.
 */
const val DEFAULT_SYNC_PORT = 24533
const val DISCOVERY_PORT = 24534

/** If the sync port is taken, try the next few and advertise whichever we got. */
const val PORT_FALLBACKS = 8

// ---------------------------------------------------------------- merge (pure)

fun mergeRecord(local: PrefRecord?, remote: PrefRecord, includeOffline: Boolean): PrefRecord {
    val l = local ?: PrefRecord()
    val favRemote = remote.favoriteAt > l.favoriteAt
    val offRemote = includeOffline && remote.offlineAt > l.offlineAt
    return PrefRecord(
        favorite = if (favRemote) remote.favorite else l.favorite,
        favoriteAt = maxOf(l.favoriteAt, remote.favoriteAt),
        lastPlayedAt = maxOf(l.lastPlayedAt, remote.lastPlayedAt),
        offline = if (offRemote) remote.offline else l.offline,
        offlineAt = if (includeOffline) maxOf(l.offlineAt, remote.offlineAt) else l.offlineAt,
    )
}

/** Records (by playlist id) whose merged value differs from [local]; apply these locally. */
fun mergeChanges(
    local: Map<String, PrefRecord>,
    remote: Map<String, PrefRecord>,
    includeOffline: Boolean,
): Map<String, PrefRecord> = buildMap {
    remote.forEach { (id, r) ->
        val before = local[id] ?: PrefRecord()
        val merged = mergeRecord(local[id], r, includeOffline)
        if (merged != before) put(id, merged)
    }
}

/** What we send: download-list fields are blanked unless we share them. */
fun outgoing(local: Map<String, PrefRecord>, shareOffline: Boolean): Map<String, PrefRecord> =
    if (shareOffline) local else local.mapValues { it.value.copy(offline = false, offlineAt = 0) }
