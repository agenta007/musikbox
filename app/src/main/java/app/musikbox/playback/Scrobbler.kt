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
        if (auto || (duration > 0 && oldPosition.positionMs >= threshold)) send(previous.mediaId, submission = true)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            player.currentMediaItem?.mediaId?.let { send(it, submission = true) }
        }
    }

    private fun send(id: String, submission: Boolean) {
        scope.launch {
            runCatching { api.scrobble(id, submission) }
                .onFailure { Log.d("Scrobbler", "scrobble failed (offline?): ${it.message}") }
        }
    }
}
