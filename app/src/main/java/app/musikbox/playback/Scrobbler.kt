package app.musikbox.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.musikbox.data.remote.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Reports "now playing" on every track start and a play (submission) once a track
 * finished or was heard for half its length / 4 minutes — the Last.fm convention.
 */
class Scrobbler(
    private val player: Player,
    private val api: SubsonicClient,
    private val scope: CoroutineScope,
    /** A song from this playlist was played through. */
    private val onPlayedFrom: suspend (playlistId: String) -> Unit = {},
) : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.mediaId?.let { send(it, submission = false) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val previous = oldPosition.mediaItem ?: return
        val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        if (!auto && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
        val duration = previous.mediaMetadata.durationMs ?: 0L
        val threshold = min(duration / 2, 240_000L)
        if (auto || (duration > 0 && oldPosition.positionMs >= threshold)) played(previous)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            player.currentMediaItem?.let(::played)
        }
    }

    private fun played(item: MediaItem) {
        send(item.mediaId, submission = true)
        val playlist = item.sourcePlaylistId() ?: return
        if (isRadioId(item.mediaId)) return
        scope.launch { runCatching { onPlayedFrom(playlist) } }
    }

    private fun send(id: String, submission: Boolean) {
        if (isRadioId(id)) return // radio stations aren't in the library
        scope.launch {
            runCatching { api.scrobble(id, submission) }
                .onFailure { Log.d("Scrobbler", "scrobble failed (offline?): ${it.message}") }
        }
    }
}
