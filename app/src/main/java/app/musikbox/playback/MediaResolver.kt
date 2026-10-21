package app.musikbox.playback

import androidx.media3.common.MediaItem
import app.musikbox.data.remote.SubsonicClient
import app.musikbox.data.settings.SettingsStore

/** Turns a metadata-only [MediaItem] into a playable one, preferring the offline copy. */
class MediaResolver(
    private val api: SubsonicClient,
    private val settings: SettingsStore,
    private val offline: OfflineManager,
) {
    fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val id = item.mediaId
        val bitrate = if (offline.isDownloaded(id)) 0 else settings.current.streamBitrate
        val url = api.streamUrl(id, bitrate) ?: return item
        return item.buildUpon()
            .setUri(url)
            .setCustomCacheKey(cacheKeyFor(id, bitrate))
            .build()
    }
}
