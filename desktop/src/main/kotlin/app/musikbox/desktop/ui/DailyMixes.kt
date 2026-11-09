package app.musikbox.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.data.remote.GenreDto
import app.musikbox.desktop.data.toSong
import app.musikbox.discover.DailyMix
import app.musikbox.discover.MAX_MIX_GENRES
import app.musikbox.ui.components.songsLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DailyMixes() {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val ui by c.dailyMixes.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.dailyMixes.ensureFresh() }

    Column(Modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily mixes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Songs you like and a few you haven't heard, new every day",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { c.dailyMixes.loadGenres(); picking = true }) { Text("Pick genres") }
        }
        Spacer(Modifier.height(10.dp))
        when {
            ui.mixes.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(ui.mixes, key = { it.genre }) { mix ->
                    MixCard(
                        mix,
                        onPlay = { c.player.playSongs(mix.songs.map { it.toSong() }, 0, shuffle = false) },
                        onSave = {
                            c.scope.launch {
                                val day = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                                val name = "${mix.genre} Mix · $day"
                                val r = c.library.createPlaylist(name, mix.songs.map { it.id })
                                snackbar.showSnackbar(if (r.isSuccess) "Saved as “$name”" else "Couldn't save: ${r.exceptionOrNull()?.message}")
                            }
                        },
                    )
                }
            }
            ui.loading -> Text("Making today's mixes…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.error != null -> Text("Couldn't make the mixes: ${ui.error}", color = MaterialTheme.colorScheme.error)
            else -> Text(
                "No mixes yet. They need songs with genre tags, at least 5 in a genre.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) {
        GenrePicker(
            available = ui.available,
            initial = ui.picked.ifEmpty { ui.mixes.map { it.genre } },
            onDismiss = { picking = false },
            onPick = { picking = false; c.dailyMixes.pick(it) },
        )
    }
}

@Composable
private fun MixCard(mix: DailyMix, onPlay: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.width(176.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPlay).pointerHoverIcon(PointerIcon.Hand).padding(4.dp)) {
        CoverArt(mix.songs.firstOrNull()?.coverArt, Modifier.size(168.dp), 400, RoundedCornerShape(10.dp), iconSize = 40.dp, seed = mix.genre)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${mix.genre} Mix", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(songsLabel(mix.songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist", Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePicker(available: List<GenreDto>, initial: List<String>, onDismiss: () -> Unit, onPick: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val shown = available.filter { it.value.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Pick up to $MAX_MIX_GENRES genres") },
        text = {
            Column(Modifier.width(520.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search genres") }, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().tracksTextFocus(),
                )
                Spacer(Modifier.height(10.dp))
                Text("${selected.size} of $MAX_MIX_GENRES picked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (available.isEmpty()) {
                    Text("Your server hasn't listed any genres. Songs need genre tags to show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { g ->
                        val on = g.value in selected
                        FilterChip(
                            selected = on,
                            enabled = on || selected.size < MAX_MIX_GENRES,
                            onClick = { selected = if (on) selected - g.value else selected + g.value },
                            label = { Text("${g.value}  ${g.songCount}") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }, enabled = selected.isNotEmpty()) { Text("Make mixes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
