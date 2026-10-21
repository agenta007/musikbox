package app.musikbox.playback

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.musikbox.data.local.SongEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private const val EXTRA_SONG = "musikbox.song"
private const val EXTRA_PLAYLIST = "musikbox.playlist"

private val json = Json { ignoreUnknownKeys = true }
private val songCache = ConcurrentHashMap<String, SongEntity>()

/**
 * Items carry only metadata; the playback service resolves the stream URI
 * (see [MediaResolver]) so auth and quality are decided in one place.
 */
fun SongEntity.toMediaItem(coverUrl: String?, playlistId: String?): MediaItem {
    songCache[id] = this
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(artist)
        .setGenre(genre)
        .setTrackNumber(track)
        .setReleaseYear(year)
        .setDurationMs(durationSec * 1000L)
        .setArtworkUri(coverUrl?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(bundleOf(EXTRA_SONG to json.encodeToString(this), EXTRA_PLAYLIST to playlistId))
        .build()
    return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
}

fun MediaItem.song(): SongEntity? =
    songCache[mediaId] ?: mediaMetadata.extras?.getString(EXTRA_SONG)
        ?.let { runCatching { json.decodeFromString<SongEntity>(it) }.getOrNull() }
        ?.also { songCache[mediaId] = it }

fun MediaItem.sourcePlaylistId(): String? = mediaMetadata.extras?.getString(EXTRA_PLAYLIST)
