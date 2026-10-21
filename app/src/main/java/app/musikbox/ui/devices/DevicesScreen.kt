package app.musikbox.ui.devices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.sync.Peer
import app.musikbox.sync.PeerState
import app.musikbox.sync.SyncCrypto
import app.musikbox.sync.SyncEngine
import app.musikbox.sync.toEntity
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.LocalSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val status by container.sync.status.collectAsStateWithLifecycle()
    val peers by container.sync.peers.collectAsStateWithLifecycle()
    val scope = container.scope
    var newPeer by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }

    fun addPeer() {
        val raw = newPeer.trim()
        if (SyncEngine.parseAddress(raw) == null) {
            scope.launch { snackbar.showSnackbar("Enter an IP, optionally with :port") }
            return
        }
        scope.launch {
            container.settings.setManualPeers((settings.manualPeers + raw).distinct())
            container.sync.refreshManualPeers()
        }
        newPeer = ""
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Devices & sync", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = container.sync::syncNow, enabled = status.running && !status.syncing) { Icon(Icons.Rounded.Sync, "Sync now") }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Text(
                    "Favourites, recently played and (optionally) the download list stay in sync with your other musikbox apps on the same account.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(container.syncHost.deviceName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (!status.running) "Sync is off" else status.error ?: "Listening for your other devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.syncEnabled, onCheckedChange = { on ->
                            scope.launch {
                                container.settings.setSyncEnabled(on)
                                if (on) container.startSync() else container.stopSync()
                            }
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("LISTEN ADDRESSES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (status.addresses.isEmpty()) {
                        Text("Not connected to a network", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    status.addresses.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${a.ip}:${status.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(8.dp))
                            Text(a.iface, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString("${a.ip}:${status.port}")) }) {
                                Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(18.dp))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Share download list", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Download what your other devices download (both must enable this)",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.shareDownloadList, onCheckedChange = { on ->
                            scope.launch { container.settings.setShareDownloadList(on); container.sync.syncNow() }
                        })
                    }
                    ServerSyncRow()
                }
                Spacer(Modifier.height(18.dp))
                Text("Other devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPeer, onValueChange = { newPeer = it }, singleLine = true,
                        placeholder = { Text("Add by IP, e.g. 192.168.1.20") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addPeer() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = ::addPeer, enabled = newPeer.isNotBlank()) { Text("Add") }
                }
                if (peers.isEmpty()) {
                    Text(
                        "No other devices yet. musikbox on your computer appears here automatically on the same Wi-Fi; over a VPN, add it by IP.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            items(peers, key = { it.key }) { p ->
                PeerCard(
                    peer = p, now = now,
                    onContinue = {
                        scope.launch {
                            val np = runCatching { container.sync.takeOver(p) }.getOrNull()
                            if (np == null || np.queue.isEmpty()) {
                                snackbar.showSnackbar("${p.displayName} isn't playing anything")
                            } else {
                                container.player.resumeQueue(np.queue.map { it.toEntity() }, np.index, np.positionMs, np.playlistId)
                                snackbar.showSnackbar("Continuing from ${p.displayName}")
                            }
                        }
                    },
                    onRemove = if (p.manual) ({
                        scope.launch {
                            container.settings.setManualPeers(
                                settings.manualPeers.filter { raw -> SyncEngine.parseAddress(raw)?.let { (h, port) -> "$h:$port" } != p.key },
                            )
                            container.sync.refreshManualPeers()
                        }
                    }) else null,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
    ) { content() }
}

/** Saved playlists / recents sync through the server too, so devices needn't ever meet on a network. */
@Composable
private fun ServerSyncRow() {
    val container = LocalAppContainer.current
    val cloud by container.cloud.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cloud.lastSyncAt) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Through your server", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    cloud.syncing -> "Syncing…"
                    cloud.error != null -> "Couldn't reach the server — will retry"
                    cloud.lastSyncAt > 0 -> "Saved playlists & recents up to date · " + ago(now - cloud.lastSyncAt)
                    else -> "Waiting for the first sync"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cloud.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { container.cloud.requestSync(0) }, enabled = !cloud.syncing) { Text("Sync") }
    }
}

@Composable
private fun PeerCard(peer: Peer, now: Long, onContinue: () -> Unit, onRemove: (() -> Unit)?) {
    val stateColor = when (peer.state) {
        PeerState.Synced -> MaterialTheme.colorScheme.primary
        PeerState.Online, PeerState.Unknown, PeerState.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (peer.platform) { "android" -> Icons.Rounded.PhoneAndroid; "linux" -> Icons.Rounded.DesktopWindows; else -> Icons.Rounded.Devices },
                null,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    peer.state.label, style = MaterialTheme.typography.labelMedium, color = stateColor,
                )
                Text(
                    buildString {
                        append(peer.key)
                        if (peer.lastSyncAt > 0) append(" · ").append(ago(now - peer.lastSyncAt))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onRemove?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Close, "Remove") } }
        }
        peer.nowPlaying?.current?.let { s ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (peer.nowPlaying?.playing == true) "▶ " else "⏸ ") + s.title + (s.artist?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onContinue) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Continue here")
                }
            }
        }
    }
}

fun ago(ms: Long): String = when {
    ms < 10_000 -> "synced just now"
    ms < 60_000 -> "synced ${ms / 1000}s ago"
    ms < 3_600_000 -> "synced ${ms / 60_000} min ago"
    else -> "synced ${ms / 3_600_000} h ago"
}
