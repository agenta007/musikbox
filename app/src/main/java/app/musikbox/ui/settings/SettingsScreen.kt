package app.musikbox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.BuildConfig
import app.musikbox.data.SyncState
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.LocalSnackbar
import app.musikbox.ui.components.ConfirmDialog
import app.musikbox.ui.components.formatBytes
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import app.musikbox.ui.downloads.DownloadRulesCard

private val bitrates = listOf(0 to "Original", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps", 96 to "96 kbps")
private val cacheSizes = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB", 16384 to "16 GB")

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit, onOpenDownloads: () -> Unit, onOpenDevices: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val sync by container.library.syncState.collectAsStateWithLifecycle()
    val downloaded by container.offline.downloaded.collectAsStateWithLifecycle()
    val scope = container.scope

    var downloadBytes by remember { mutableLongStateOf(0L) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(downloaded.size, refreshTick) {
        downloadBytes = container.offline.bytesUsed()
        cacheBytes = container.streamCacheBytes()
    }

    var pickBitrate by remember { mutableStateOf(false) }
    var pickCache by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                val creds = settings.credentials
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            creds?.username?.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(creds?.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            creds?.serverUrl.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { confirmSignOut = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, "Sign out", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { Section("Devices") }
            item {
                val peers by container.sync.peers.collectAsStateWithLifecycle()
                val syncStatus by container.sync.status.collectAsStateWithLifecycle()
                Setting(
                    Icons.Rounded.Devices,
                    "Devices & sync",
                    when {
                        !settings.syncEnabled -> "Off"
                        settings.syncKey == null -> "Needs your password once to turn on"
                        else -> "${peers.count { it.state == app.musikbox.sync.PeerState.Synced }} synced · listening on " +
                            (syncStatus.addresses.firstOrNull()?.let { "${it.ip}:${syncStatus.port}" } ?: "port ${syncStatus.port}")
                    },
                    onClick = onOpenDevices,
                )
            }
            item { Section("Library") }
            item {
                Setting(
                    Icons.Rounded.Sync,
                    "Sync playlists now",
                    when (sync) {
                        SyncState.Running -> "Syncing…"
                        is SyncState.Failed -> "Last attempt failed: ${(sync as SyncState.Failed).message}"
                        SyncState.Idle -> if (settings.lastSyncAt > 0) {
                            "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt))
                        } else "Never synced"
                    },
                ) {
                    scope.launch {
                        val r = container.library.sync(force = true)
                        snackbar.showSnackbar(if (r.isSuccess) "Playlists up to date" else "Sync failed")
                    }
                }
            }

            item { Section("Playback") }
            item {
                Setting(
                    Icons.Rounded.GraphicEq,
                    "Streaming quality",
                    bitrates.firstOrNull { it.first == settings.streamBitrate }?.second
                        ?.let { if (settings.streamBitrate == 0) "$it — no transcoding" else "$it MP3 (server transcodes)" } ?: "",
                ) { pickBitrate = true }
            }

            item { Section("Offline") }
            item { DownloadRulesCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            item {
                Toggle(Icons.Rounded.Wifi, "Download on Wi-Fi only", "Pause downloads on mobile data", settings.wifiOnlyDownloads) { on ->
                    scope.launch { container.settings.setWifiOnlyDownloads(on) }
                }
            }
            item {
                Setting(
                    Icons.Rounded.SdStorage,
                    "Manage downloads",
                    "${downloaded.size} songs · ${formatBytes(downloadBytes)} · download list, retries, activity log",
                    onClick = onOpenDownloads,
                )
            }

            item { Section("Cache") }
            item {
                Setting(
                    Icons.Rounded.Storage,
                    "Streaming cache size",
                    "${cacheSizes.firstOrNull { it.first == settings.cacheSizeMb }?.second ?: "${settings.cacheSizeMb} MB"} · " +
                        "${formatBytes(cacheBytes)} used · songs you play are kept for instant replay",
                ) { pickCache = true }
            }
            item {
                Setting(Icons.Rounded.DeleteSweep, "Clear streaming cache", "Downloads are kept") {
                    scope.launch {
                        container.clearStreamCache()
                        refreshTick++
                        snackbar.showSnackbar("Cache cleared")
                    }
                }
            }

            item { Section("Appearance") }
            item {
                Toggle(Icons.Rounded.Contrast, "Pure black", "True black background for OLED screens", settings.pureBlack) { on ->
                    scope.launch { container.settings.setPureBlack(on) }
                }
            }

            item { Section("About") }
            item {
                Setting(Icons.Rounded.Info, "musikbox ${BuildConfig.VERSION_NAME}", "A playlist-first client for Navidrome & Subsonic", onClick = null)
            }
        }
    }

    if (pickBitrate) {
        ChoiceDialog("Streaming quality", bitrates, settings.streamBitrate, onDismiss = { pickBitrate = false }) {
            pickBitrate = false
            scope.launch { container.settings.setStreamBitrate(it) }
        }
    }
    if (pickCache) {
        ChoiceDialog(
            "Streaming cache size", cacheSizes, settings.cacheSizeMb,
            footnote = "Takes effect the next time the app starts.",
            onDismiss = { pickCache = false },
        ) {
            pickCache = false
            scope.launch { container.settings.setCacheSizeMb(it) }
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            "Sign out?",
            "Cached playlists, downloads and the play queue are removed from this device.",
            "Sign out",
            onDismiss = { confirmSignOut = false },
        ) {
            confirmSignOut = false
            scope.launch {
                container.signOut()
                onSignedOut()
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Setting(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 24.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    footnote: String? = null,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(value) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                footnote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
