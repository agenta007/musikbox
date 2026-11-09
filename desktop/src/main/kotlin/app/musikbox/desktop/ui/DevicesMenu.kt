package app.musikbox.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.sync.Peer
import app.musikbox.sync.PeerState
import app.musikbox.sync.RemoteCommand
import app.musikbox.desktop.sync.toSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The devices menu: what each of your devices is playing, a remote control for each, and moving
 * or sharing playback between them. Opened from the player bar.
 */
@Composable
fun DevicesDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val peers by c.sync.peers.collectAsState()
    val status by c.sync.status.collectAsState()
    val linked by c.group.linked.collectAsState()
    val player by c.player.state.collectAsState()

    // While the menu is open, every device's status is a couple of seconds old at most.
    LaunchedEffect(Unit) {
        c.sync.syncNow()
        while (true) {
            c.sync.refreshAllNowPlaying()
            delay(2_000)
        }
    }

    val usable = peers.filter { it.state == PeerState.Synced && it.id != null }
    val others = peers.filter { it !in usable && it.state != PeerState.Offline }

    fun act(block: suspend () -> Result<*>) = c.scope.launch {
        block().onFailure { snackbar.showSnackbar(it.message ?: "That didn't work") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Play on") },
        text = {
            Column(Modifier.width(600.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceCard(
                    icon = Icons.Rounded.Computer,
                    name = "${c.syncHost.deviceName} (this device)",
                    playing = player.song?.let { s -> listOfNotNull(s.title, s.artist).joinToString(" · ") },
                    isPlaying = player.isPlaying,
                    onToggle = c.player::togglePlay.takeIf { player.song != null },
                    onNext = c.player::next.takeIf { player.hasNext },
                    footer = if (linked.isNotEmpty()) {
                        { Text("Also playing on ${usable.filter { it.id in linked }.joinToString { it.displayName }.ifEmpty { "a device that's out of reach" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
                if (!status.running || !status.keyReady) {
                    Text(
                        "Device sync is off on this computer. Turn it on in Devices to see and control your other devices.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (usable.isEmpty()) {
                    Text(
                        "No other devices found. Open musikbox on another device signed in to the same account, on this network, with device sync on.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usable.forEach { peer ->
                    val id = peer.id!!
                    val np = peer.nowPlaying
                    DeviceCard(
                        icon = platformIcon(peer),
                        name = peer.displayName,
                        playing = np?.current?.let { s -> listOfNotNull(s.title, s.artist).joinToString(" · ") },
                        isPlaying = np?.playing == true,
                        onToggle = np?.let {
                            { act { c.sync.control(peer, RemoteCommand(if (it.playing) RemoteCommand.PAUSE else RemoteCommand.PLAY)) } }
                        },
                        onNext = np?.let { { act { c.sync.control(peer, RemoteCommand(RemoteCommand.NEXT)) } } },
                        footer = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (np != null) TextButton(onClick = {
                                    act {
                                        runCatching {
                                            val taken = c.sync.takeOver(peer) ?: error("${peer.displayName} isn't playing anything")
                                            c.group.unlink(id)
                                            c.player.resumeQueue(taken.queue.map { it.toSong() }, taken.index, taken.positionMs, taken.playlistId)
                                        }
                                    }
                                }) { Text("Play here") }
                                if (player.song != null) TextButton(onClick = {
                                    act {
                                        val mine = c.syncHost.nowPlaying() ?: return@act Result.failure<Unit>(IllegalStateException("Nothing is playing here"))
                                        c.sync.control(peer, RemoteCommand(RemoteCommand.LOAD, queue = mine.copy(playing = true))).onSuccess {
                                            c.group.unlink(id)
                                            c.player.pause()
                                        }
                                    }
                                }) { Text("Play there") }
                                if (player.song != null || id in linked) FilterChip(
                                    selected = id in linked,
                                    onClick = {
                                        if (id in linked) c.group.unlink(id)
                                        else c.scope.launch { c.group.link(id, c.syncHost.nowPlaying()) }
                                    },
                                    label = { Text(if (id in linked) "Playing the same" else "Also play there") },
                                    leadingIcon = { Icon(Icons.Rounded.SpeakerGroup, null, Modifier.size(18.dp)) },
                                )
                            }
                        },
                    )
                }
                if (others.isNotEmpty()) {
                    HorizontalDivider()
                    others.forEach { p ->
                        Text(
                            "${p.displayName}: ${p.state.label}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onDismiss(); nav.go(Screen.Devices) }) { Text("Device settings") } },
    )
}

private fun platformIcon(peer: Peer) = if (peer.platform == "android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer

@Composable
private fun DeviceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    playing: String?,
    isPlaying: Boolean,
    onToggle: (() -> Unit)?,
    onNext: (() -> Unit)?,
    footer: (@Composable () -> Unit)?,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = if (footer != null) 4.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            playing == null -> "Not playing"
                            isPlaying -> playing
                            else -> "Paused: $playing"
                        },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onToggle != null) IconButton(onClick = onToggle) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause on $name" else "Play on $name")
                }
                if (onNext != null) IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next on $name") }
            }
            footer?.invoke()
        }
    }
}
