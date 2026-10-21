package app.musikbox.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.playback.DownloadStatus
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.components.formatBytes
import app.musikbox.ui.components.songsLabel

fun playlistStatusText(d: PlaylistDownload, status: DownloadStatus): String {
    val counts = "${d.downloaded} of ${d.total}"
    return when {
        d.complete -> "${songsLabel(d.total)} · ${formatBytes(d.bytes)}"
        status.paused -> "Paused · $counts"
        d.failed > 0 && d.pending == 0 -> "$counts · ${d.failed} failed"
        d.pending > 0 && status.waitingFor != null -> "Waiting for ${status.waitingFor} · $counts"
        d.pending > 0 -> "Downloading · $counts" + if (d.failed > 0) " · ${d.failed} failed" else ""
        else -> "Not downloaded · $counts"
    }
}

private data class Overall(val icon: ImageVector, val title: String, val tint: @Composable () -> Color)

@Composable
private fun overall(s: DownloadsSummary, status: DownloadStatus): Overall {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    return when {
        !status.ready -> Overall(Icons.Rounded.HourglassEmpty, "Loading downloads…") { muted }
        s.totalSongs == 0 -> Overall(Icons.Rounded.Download, "Download list is empty") { muted }
        status.paused -> Overall(Icons.Rounded.PauseCircle, "Downloads paused") { muted }
        s.pendingSongs > 0 && status.waitingFor != null -> Overall(Icons.Rounded.CloudOff, "Waiting for ${status.waitingFor}") { muted }
        s.pendingSongs > 0 -> Overall(Icons.Rounded.Download, "Downloading ${songsLabel(s.pendingSongs)}") { primary }
        s.failedSongs > 0 -> Overall(Icons.Rounded.ErrorOutline, "${songsLabel(s.failedSongs)} failed") { error }
        s.complete -> Overall(Icons.Rounded.DownloadDone, "All ${songsLabel(s.totalSongs)} downloaded") { primary }
        else -> Overall(Icons.Rounded.Download, "${s.downloadedSongs} of ${songsLabel(s.totalSongs)} downloaded") { muted }
    }
}

/** Overall status with progress, speed and the pause / retry controls. */
@Composable
fun DownloadStatusCard(
    summary: DownloadsSummary,
    status: DownloadStatus,
    onTogglePause: () -> Unit,
    onRetryFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val o = overall(summary, status)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(o.icon, null, tint = o.tint(), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${summary.downloadedSongs} of ${songsLabel(summary.totalSongs)} · ${formatBytes(summary.bytes)}")
                        if (status.bytesPerSecond > 0 && !status.paused) append(" · ${formatBytes(status.bytesPerSecond)}/s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (summary.totalSongs > 0 && !summary.complete) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { summary.fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {},
            )
        }
        if (summary.totalSongs > 0) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onTogglePause) {
                    Icon(if (status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.paused) "Resume" else "Pause")
                }
                if (summary.failedSongs > 0) {
                    OutlinedButton(onClick = onRetryFailed) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry ${summary.failedSongs} failed")
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDownloadRow(
    d: PlaylistDownload,
    status: DownloadStatus,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = d.failed > 0 && d.pending == 0
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(d.item.playlist.coverArt, Modifier.size(48.dp), sizePx = 160, shape = RoundedCornerShape(8.dp), seed = d.item.id)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistStatusText(d, status),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    problem -> MaterialTheme.colorScheme.error
                    d.complete || status.paused -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!d.complete) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { d.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    drawStopIndicator = {},
                )
            }
        }
        when {
            d.complete -> Icon(
                Icons.Rounded.DownloadDone, "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
            problem -> Icon(
                Icons.Rounded.ErrorOutline, "Some songs failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "Download actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
