package app.musikbox.desktop.ui

import androidx.compose.material3.OutlinedTextField
import app.musikbox.lyrics.LyricsService
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.data.SyncState
import app.musikbox.desktop.player.Mpv
import app.musikbox.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val bitrates = listOf(0 to "Original (no transcoding)", 320 to "320 kbps MP3", 256 to "256 kbps MP3", 192 to "192 kbps MP3", 128 to "128 kbps MP3", 96 to "96 kbps MP3")
private val cacheSizes = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB", 16384 to "16 GB")

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val settings by c.settings.state.collectAsState()
    val sync by c.library.syncState.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmRemoveDl by remember { mutableStateOf(false) }
    val mpvVersion = remember { Mpv.version() ?: "not found" }
    val listState = rememberLazyListState()

    LaunchedEffect(downloaded.size, tick) {
        withContext(Dispatchers.IO) {
            downloadBytes = c.downloads.bytesUsed()
            cacheBytes = c.streamCache.bytesUsed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
            item { Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 20.dp)) }
            item {
                Row(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text(settings.username?.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(settings.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(settings.serverUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
                }
            }

            item { Section("Library") }
            item {
                SettingRow(
                    "Sync playlists now",
                    when (val s = sync) {
                        SyncState.Running -> "Syncing…"
                        is SyncState.Failed -> "Last attempt failed: ${s.message}"
                        SyncState.Idle -> if (settings.lastSyncAt > 0) "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt)) else "Never synced"
                    },
                ) {
                    TextButton(onClick = {
                        c.scope.launch { snackbar.showSnackbar(if (c.library.sync(force = true).isSuccess) "Playlists up to date" else "Sync failed") }
                    }) { Text("Sync") }
                }
            }

            item { Section("Playback") }
            item {
                SettingRow("Streaming quality", "Downloads always keep the original file") {
                    Choice(bitrates, settings.streamBitrate) { v -> c.settings.update { it.copy(streamBitrate = v) } }
                }
            }

            item { Section("Lyrics") }
            item {
                SettingRow(
                    "Find missing lyrics online",
                    "Your server is always asked first. When it has none, ask LRCLIB — this sends the artist, title, album and length.",
                ) {
                    Switch(settings.lyricsOnline, { on -> c.settings.update { it.copy(lyricsOnline = on) } })
                }
            }
            item {
                var url by remember(settings.lyricsServer) { mutableStateOf(settings.lyricsServer) }
                SettingRow("LRCLIB server", "lrclib.net or your own instance — LRCLIB is open source") {
                    OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.width(280.dp))
                    TextButton(
                        onClick = { c.settings.update { it.copy(lyricsServer = url.trim().ifBlank { LyricsService.DEFAULT_LRCLIB }) } },
                        enabled = url.trim() != settings.lyricsServer,
                    ) { Text("Save") }
                }
            }
            item {
                SettingRow("Clear lyrics cache", "Look every song up again") {
                    TextButton(onClick = {
                        c.scope.launch {
                            c.lyrics.clearCache()
                            snackbar.showSnackbar("Lyrics cache cleared")
                        }
                    }) { Text("Clear") }
                }
            }

            item { Section("Offline") }
            item {
                DownloadRulesPanel()
            }
            item {
                SettingRow("Downloaded songs", "${downloaded.size} songs · ${formatBytes(downloadBytes)} in ${AppPaths.downloadsDir}") {
                    TextButton(onClick = { confirmRemoveDl = true }, enabled = downloaded.isNotEmpty()) { Text("Remove all", color = MaterialTheme.colorScheme.error) }
                }
            }

            item { Section("Cache") }
            item {
                SettingRow("Streaming cache size", "${formatBytes(cacheBytes)} used · songs you play are kept for instant replay") {
                    Choice(cacheSizes, settings.cacheSizeMb) { v -> c.settings.update { it.copy(cacheSizeMb = v) } }
                }
            }
            item {
                SettingRow("Clear streaming cache", "Downloads are kept") {
                    TextButton(onClick = {
                        c.scope.launch {
                            withContext(Dispatchers.IO) { c.streamCache.clear() }
                            tick++
                            snackbar.showSnackbar("Cache cleared")
                        }
                    }) { Text("Clear") }
                }
            }

            item { Section("Appearance") }
            item {
                SettingRow("Pure black", "True black background for OLED screens") {
                    Switch(settings.pureBlack, { on -> c.settings.update { it.copy(pureBlack = on) } })
                }
            }

            item { Section("Keyboard") }
            item {
                Column(Modifier.widthIn(max = 760.dp).padding(vertical = 6.dp)) {
                    listOf(
                        "Space" to "Play / pause",
                        "Ctrl + → / ←" to "Next / previous track",
                        "Ctrl + ↑ / ↓" to "Volume up / down",
                        "Ctrl + F" to "Search",
                        "Alt + ←" to "Back",
                        "Ctrl + Q" to "Quit",
                        "Media keys" to "Work system-wide via MPRIS",
                    ).forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(160.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Section("About") }
            item {
                Text(
                    "musikbox ${AppPaths.version} · $mpvVersion\nA playlist-first client for Navidrome & Subsonic",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    if (confirmRemoveDl) {
        ConfirmDialog("Remove all downloads?", "Every offline song is deleted and all playlists stop downloading. Your playlists stay cached.", "Remove", { confirmRemoveDl = false }) {
            confirmRemoveDl = false
            c.library.removeAllDownloads()
        }
    }
    if (confirmSignOut) {
        ConfirmDialog("Sign out?", "Cached playlists, downloads and the play queue are removed from this computer.", "Sign out", { confirmSignOut = false }) {
            confirmSignOut = false
            c.scope.launch { c.signOut() }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 28.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
private fun Choice(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == selected }?.second ?: "$selected") }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
            options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (v == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { open = false; onSelect(v) },
                )
            }
        }
    }
}

