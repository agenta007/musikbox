package app.musikbox.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musikbox.desktop.sync.toSong
import app.musikbox.sync.Peer
import app.musikbox.sync.PeerState
import app.musikbox.sync.SyncCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen() {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current
    val settings by c.settings.state.collectAsState()
    val status by c.sync.status.collectAsState()
    val peers by c.sync.peers.collectAsState()
    val log by c.syncHost.logLines.collectAsState()
    var newPeer by remember { mutableStateOf("") }
    var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName ?: c.syncHost.deviceName) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }

    fun setEnabled(on: Boolean) {
        c.settings.update { it.copy(syncEnabled = on) }
        if (on) c.startSync() else c.stopSync()
    }

    fun addPeer() {
        val raw = newPeer.trim()
        if (app.musikbox.sync.SyncEngine.parseAddress(raw) == null) {
            c.scope.launch { snackbar.showSnackbar("Enter an IP, optionally with :port") }
            return
        }
        c.settings.update { it.copy(manualPeers = (it.manualPeers + raw).distinct()) }
        c.sync.refreshManualPeers()
        newPeer = ""
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text("Devices & sync", style = MaterialTheme.typography.displaySmall)
            Text(
                "Favourites, recently played and (optionally) the download list stay in sync between your musikbox apps on the same Navidrome account. Continue playback from any of them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        // ---------------------------------------------------------------- this device
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("This device", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!status.running) "Sync is off" else status.error ?: "Listening for other musikbox apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Sync", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = settings.syncEnabled, onCheckedChange = ::setEnabled)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Device name") }, modifier = Modifier.width(320.dp).tracksTextFocus(),
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(
                        enabled = name.isNotBlank() && name != c.syncHost.deviceName,
                        onClick = { c.settings.update { it.copy(deviceName = name.trim()) } },
                    ) { Text("Rename") }
                }
                Spacer(Modifier.height(14.dp))
                Text("LISTEN ADDRESSES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (status.addresses.isEmpty()) {
                    Text("No network interface with an IPv4 address", style = MaterialTheme.typography.bodySmall)
                }
                status.addresses.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${a.ip}:${status.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        Text(a.iface, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { clipboard.setText(AnnotatedString("${a.ip}:${status.port}")) }) {
                            Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share download list", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Playlists marked for download here are downloaded on your other devices too (both must enable this).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.shareDownloadList, onCheckedChange = { on ->
                        c.settings.update { it.copy(shareDownloadList = on) }; c.sync.syncNow()
                    })
                }
                Spacer(Modifier.height(14.dp))
                ServerSyncRow()
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---------------------------------------------------------------- other devices
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Other devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (status.lastSyncAt > 0) {
                    Text("Last sync ${ago(now - status.lastSyncAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                OutlinedButton(onClick = c.sync::syncNow, enabled = status.running && !status.syncing) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (status.syncing) "Syncing…" else "Sync now")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPeer, onValueChange = { newPeer = it }, singleLine = true,
                    label = { Text("Add device by IP (e.g. 192.168.1.20 or 10.8.0.3:24533)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addPeer() }),
                    modifier = Modifier.width(460.dp).tracksTextFocus(),
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = ::addPeer, enabled = newPeer.isNotBlank()) { Text("Add") }
            }
            Spacer(Modifier.height(12.dp))
            if (peers.isEmpty()) {
                Text(
                    "No other devices yet. Open musikbox on your phone on the same network — it appears here automatically. Across VPNs or guest Wi-Fi, add it by IP.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(peers, key = { it.key }) { p ->
            PeerCard(
                peer = p, now = now,
                onContinue = {
                    c.scope.launch {
                        val np = runCatching { c.sync.takeOver(p) }.getOrNull()
                        if (np == null || np.queue.isEmpty()) {
                            snackbar.showSnackbar("${p.displayName} isn't playing anything")
                        } else {
                            c.player.resumeQueue(np.queue.map { it.toSong() }, np.index, np.positionMs, np.playlistId)
                            snackbar.showSnackbar("Continuing from ${p.displayName}")
                        }
                    }
                },
                onRemove = if (p.manual) ({
                    c.settings.update { s -> s.copy(manualPeers = s.manualPeers.filter { app.musikbox.sync.SyncEngine.parseAddress(it)?.let { (h, port) -> "$h:$port" } != p.key }) }
                    c.sync.refreshManualPeers()
                }) else null,
            )
            Spacer(Modifier.height(10.dp))
        }

        // ---------------------------------------------------------------- log
        item {
            Spacer(Modifier.height(16.dp))
            Text("Sync log", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            if (log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(log.asReversed().take(60)) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 900.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
    ) { content() }
}

/** Saved playlists / recents also sync through the server, so devices needn't ever meet on a network. */
@Composable
private fun ServerSyncRow() {
    val c = LocalContainer.current
    val cloud by c.cloud.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cloud.lastSyncAt) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Through your server", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    cloud.syncing -> "Syncing…"
                    cloud.error != null -> "Couldn't reach the server — will retry (${cloud.error})"
                    cloud.lastSyncAt > 0 -> "Saved playlists, recents and the key are up to date · synced ${ago(now - cloud.lastSyncAt)}"
                    else -> "Waiting for the first sync"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cloud.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { c.cloud.requestSync(0) }, enabled = !cloud.syncing) { Text("Sync now") }
    }
}

@Composable
private fun PeerCard(peer: Peer, now: Long, onContinue: () -> Unit, onRemove: (() -> Unit)?) {
    val stateColor = when (peer.state) {
        PeerState.Synced -> MaterialTheme.colorScheme.primary
        PeerState.Online, PeerState.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
        PeerState.Offline -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.error
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (peer.platform) { "android" -> Icons.Rounded.PhoneAndroid; "linux" -> Icons.Rounded.DesktopWindows; else -> Icons.Rounded.Devices },
                null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        peer.state.label, style = MaterialTheme.typography.labelMedium, color = stateColor,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(stateColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    buildString {
                        append(peer.key)
                        append(if (peer.manual) " · added by IP" else " · found on the network")
                        if (peer.lastSyncAt > 0) append(" · synced ${ago(now - peer.lastSyncAt)}")
                        peer.message?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                peer.nowPlaying?.current?.let { s ->
                    Text(
                        (if (peer.nowPlaying?.playing == true) "▶ " else "⏸ ") + s.title + (s.artist?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified,
                    )
                }
            }
            if (peer.nowPlaying?.current != null) {
                Button(onClick = onContinue) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Continue here")
                }
            }
            onRemove?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Close, "Remove") } }
        }
    }
}

fun ago(ms: Long): String = when {
    ms < 10_000 -> "just now"
    ms < 60_000 -> "${ms / 1000}s ago"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

