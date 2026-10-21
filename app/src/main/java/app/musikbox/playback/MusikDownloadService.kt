package app.musikbox.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.musikbox.MusikboxApp
import app.musikbox.R
import app.musikbox.ui.MainActivity

@OptIn(UnstableApi::class)
class MusikDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.channel_downloads,
    R.string.channel_downloads_desc,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager =
        (application as MusikboxApp).container.offline.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val remaining = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return helper.buildProgressNotification(
            this, R.drawable.ic_notification, intent,
            if (remaining > 0) "Saving $remaining tracks for offline" else null,
            downloads, notMetRequirements,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 0x6d75
        const val CHANNEL_ID = "downloads"
    }
}
