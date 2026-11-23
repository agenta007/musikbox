package app.musikbox.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.musikbox.radio.RadioStation

private const val RADIO_PREFIX = "radio:"
private const val EXTRA_HLS = "musikbox.hls"

/**
 * A radio station as a player item. The stream URL travels as request metadata (it survives
 * the trip from the controller to the session); [MediaResolver] turns it into the item's URI.
 */
fun RadioStation.toMediaItem(subtitle: String): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(subtitle)
        .setStation(name)
        .setArtworkUri(logo?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(bundleOf(EXTRA_HLS to hls))
        .build()
    return MediaItem.Builder()
        .setMediaId(RADIO_PREFIX + id)
        .setMediaMetadata(metadata)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
        .build()
}

/** The station id when this item is a radio station. */
fun MediaItem.radioStationId(): String? = mediaId.takeIf { it.startsWith(RADIO_PREFIX) }?.removePrefix(RADIO_PREFIX)

fun isRadioId(mediaId: String?): Boolean = mediaId?.startsWith(RADIO_PREFIX) == true

/** The playable form of a radio item: its stream URL, HLS marked as such. */
fun MediaItem.resolveRadio(): MediaItem {
    val uri = requestMetadata.mediaUri ?: return this
    val hls = mediaMetadata.extras?.getBoolean(EXTRA_HLS) == true || uri.path.orEmpty().endsWith(".m3u8")
    return buildUpon()
        .setUri(uri)
        .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()
}

/**
 * Songs go through the caches; radio streams never do: they never end, so caching one would
 * only push songs out. Song requests carry a cache key (set by [MediaResolver]); radio ones don't.
 */
@OptIn(UnstableApi::class)
class CacheBypassDataSource(private val cached: DataSource, private val direct: DataSource) : DataSource {
    private var open: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.key != null) cached else direct
        open = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(open) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = open?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders ?: emptyMap()

    override fun close() {
        try { open?.close() } finally { open = null }
    }

    class Factory(private val cached: DataSource.Factory, private val direct: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CacheBypassDataSource(cached.createDataSource(), direct.createDataSource())
    }
}
