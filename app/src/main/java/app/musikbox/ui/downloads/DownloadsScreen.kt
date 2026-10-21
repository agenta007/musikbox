package app.musikbox.ui.downloads

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.musikbox.data.ActivityLog
import app.musikbox.playback.OfflineManager
import app.musikbox.playback.SongDlState
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.components.ConfirmDialog
import app.musikbox.ui.components.SheetRow
import app.musikbox.ui.components.formatBytes
import app.musikbox.data.autoReason
import app.musikbox.data.settings.downloadRules

@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: DownloadsViewModel = viewModel { DownloadsViewModel(container) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val addable by vm.addable.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val settings by LocalAppContainer.current.settings.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var verbose by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<PlaylistDownload?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                DownloadStatusCard(summary, status, vm::togglePause, vm::retryFailed, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                DownloadRulesCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Download list", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (addable.isNotEmpty()) TextButton(onClick = { adding = true }) {
                        Icon(Icons.Rounded.Add, null); Text("Add playlists")
                    }
                }
                if (summary.playlists.isEmpty()) {
                    Text(
                        "Empty. Add playlists to keep them on this phone.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(summary.playlists, key = { it.item.id }) { d ->
                PlaylistDownloadRow(d, status, onClick = { menuFor = d }, onMore = { menuFor = d })
            }
            if (summary.totalSongs > 0) {
                item { Text("Whole list", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)) }
                item { SheetRow(Icons.Rounded.Download, "Download all", "Resume, fetch anything missing and retry failures", vm::downloadAll) }
                item {
                    SheetRow(Icons.Rounded.Refresh, "Re-download all", "Delete every file and download the list again") {
                        confirm = Triple("Re-download everything?", "All ${summary.totalSongs} songs are deleted and fetched again.", vm::redownloadAll)
                    }
                }
                item {
                    SheetRow(Icons.Rounded.FileDownloadOff, "Delete downloaded files", "Frees ${formatBytes(summary.bytes)}. Keeps the list; tap Download all to get them back") {
                        confirm = Triple("Delete downloaded files?", "The download list is kept and downloads are paused until you tap Download all.", vm::deleteFiles)
                    }
                }
                item {
                    SheetRow(Icons.Rounded.DeleteSweep, "Clear download list", "Remove every playlist from the list and delete the files") {
                        confirm = Triple("Clear the download list?", "All playlists leave the list and every downloaded song is deleted.", vm::clearList)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Activity log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilterChip(selected = verbose, onClick = { verbose = !verbose }, label = { Text("Verbose") })
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.logText(verbose))) }) { Text("Copy") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.logText(true))
                        context.startActivity(Intent.createChooser(send, "Share musikbox log"))
                    }) { Text("Share") }
                    TextButton(onClick = vm::clearLog) { Text("Clear") }
                }
            }
            val shown = log.asReversed().filter { verbose || it.level != ActivityLog.Level.Debug }.take(300)
            items(shown.size) { i ->
                val e = shown[i]
                Text(
                    e.format(),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = when (e.level) {
                        ActivityLog.Level.Error -> MaterialTheme.colorScheme.error
                        ActivityLog.Level.Warn -> MaterialTheme.colorScheme.primary
                        ActivityLog.Level.Info -> MaterialTheme.colorScheme.onSurface
                        ActivityLog.Level.Debug -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 1.dp),
                )
            }
        }
    }

    menuFor?.let { d ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Text(d.item.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                Text(playlistStatusText(d, status), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SheetRow(Icons.AutoMirrored.Rounded.QueueMusic, "Open playlist") { menuFor = null; onOpenPlaylist(d.item.id) }
                SheetRow(Icons.Rounded.Refresh, "Re-download playlist", "Delete its files and fetch them again") { menuFor = null; vm.redownload(d.item.id) }
                val auto = autoReason(d.item.id, d.item.isFavorite, settings.downloadRules)
                if (auto != null) SheetRow(Icons.Rounded.RemoveCircleOutline, "Kept automatically", auto, onClick = null)
                else SheetRow(Icons.Rounded.RemoveCircleOutline, "Remove from download list") { menuFor = null; vm.removeFromList(d.item.id) }
                SongStatusList(vm, d.item.id)
            }
        }
    }

    if (adding) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to download list", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = picked.isNotEmpty(), onClick = { vm.add(picked.toList()); adding = false }) { Text("Add ${picked.size}") }
                }
                LazyColumn {
                    items(addable, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (p.id in picked) picked - p.id else picked + p.id }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = p.id in picked, onCheckedChange = { picked = if (it) picked + p.id else picked - p.id })
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${p.playlist.songCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (title, body, action) ->
        ConfirmDialog(title, body, "Confirm", onDismiss = { confirm = null }) { confirm = null; action() }
    }
}

/** Per-song download state for one playlist: done with size, live percent, queued, or the failure reason. */
@Composable
private fun SongStatusList(vm: DownloadsViewModel, playlistId: String) {
    val tracksFlow = remember(playlistId) { vm.tracks(playlistId) }
    val tracks by tracksFlow.collectAsStateWithLifecycle(emptyList())
    val songs by vm.songs.collectAsStateWithLifecycle()
    Text(
        "Songs",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(tracks, key = { it.position }) { t ->
            val dl = songs[t.song.id]
            val (label, color) = when (dl?.state) {
                SongDlState.Completed -> "\u2713 ${formatBytes(dl.bytes)}" to MaterialTheme.colorScheme.primary
                SongDlState.Downloading -> (if (dl.percent >= 0) "\u2193 ${dl.percent.toInt()}%" else "\u2193 starting") to MaterialTheme.colorScheme.primary
                SongDlState.Queued -> "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
                SongDlState.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
                SongDlState.Failed -> (
                    "\u2717 ${dl.error ?: "failed"}" +
                        if (dl.attempts > 0) " \u00b7 retry ${dl.attempts}/${OfflineManager.MAX_AUTO_RETRIES}" else ""
                    ) to MaterialTheme.colorScheme.error
                null -> "Not downloaded" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
