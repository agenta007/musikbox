package app.musikbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.data.remote.AlbumDto
import app.musikbox.desktop.data.Song
import app.musikbox.desktop.data.toSong
import app.musikbox.discover.DiscoverSection
import app.musikbox.discover.albumNote
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.songsLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiscoverScreen() {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val ui by c.discover.state.collectAsState()
    val player by c.player.state.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val songMenu = rememberSongMenu()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { c.discover.ensureFresh() }

    val result = ui.result
    val mix: List<Song> = result?.mix?.map { it.toSong() }.orEmpty()

    fun playAlbum(a: AlbumDto) = c.scope.launch {
        runCatching { c.api.getAlbum(a.id).song.map { it.toSong() } }
            .onSuccess { if (it.isNotEmpty()) c.player.playSongs(it, 0) }
            .onFailure { snackbar.showSnackbar("Couldn't load ${a.name}: ${it.message}") }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 40.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "New to you — picked from your own library by what you play, star and skip past.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = c.discover::regenerate, enabled = !ui.loading) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New picks")
                }
            }
            Spacer(Modifier.height(20.dp))
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
            ui.error?.let { Text("Couldn't build Discover: $it", color = MaterialTheme.colorScheme.error) }
        }

        if (result != null) {
            item {
                MixHero(
                    mix = mix,
                    onPlay = { c.player.playSongs(mix, 0, shuffle = false) },
                    onShuffle = { c.player.playSongs(mix, 0, shuffle = true) },
                    onSave = {
                        c.scope.launch {
                            val name = "Discover · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                            val r = c.library.createPlaylist(name, mix.map { it.id })
                            snackbar.showSnackbar(if (r.isSuccess) "Saved as “$name”" else "Couldn't save: ${r.exceptionOrNull()?.message}")
                        }
                    },
                )
                val taste = result.profile.topGenres.take(3) + result.profile.topArtists.take(3).map { it.second }.filter { it.isNotBlank() }
                if (taste.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Based on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        taste.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
                    }
                } else {
                    Text(
                        "Play and star some music — the more musikbox knows, the better the picks. For now: a wide sample.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("Your Discover Mix", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
            }
            itemsIndexed(mix, key = { i, s -> "mix-$i-${s.id}" }) { i, song ->
                TrackRow(
                    song = song, index = i + 1,
                    isCurrent = player.song?.id == song.id, isPlaying = player.isPlaying,
                    downloaded = song.id in downloaded,
                    onPlay = { c.player.playSongs(mix, i) },
                    menuItems = { songMenu.items(song) },
                )
            }
            result.sections.forEach { section ->
                item(key = section.key) {
                    Spacer(Modifier.height(28.dp))
                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                    Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (section.albums.isNotEmpty()) AlbumRow(section, ::playAlbum)
                }
                if (section.songs.isNotEmpty()) {
                    val songs = section.songs.map { it.toSong() }
                    itemsIndexed(songs, key = { i, s -> "${section.key}-$i-${s.id}" }) { i, song ->
                        TrackRow(
                            song = song, index = null,
                            isCurrent = player.song?.id == song.id, isPlaying = player.isPlaying,
                            downloaded = song.id in downloaded,
                            onPlay = { c.player.playSongs(songs, i) },
                            menuItems = { songMenu.items(song) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MixHero(mix: List<Song>, onPlay: () -> Unit, onShuffle: () -> Unit, onSave: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val covers = mix.map { it.coverArt }.distinct().take(4)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), MaterialTheme.colorScheme.surfaceContainerHigh)))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(150.dp).clip(RoundedCornerShape(14.dp))) {
            if (covers.size >= 4) {
                Column {
                    Row { CoverArt(covers[0], Modifier.size(75.dp), 200, RoundedCornerShape(0.dp)); CoverArt(covers[1], Modifier.size(75.dp), 200, RoundedCornerShape(0.dp)) }
                    Row { CoverArt(covers[2], Modifier.size(75.dp), 200, RoundedCornerShape(0.dp)); CoverArt(covers[3], Modifier.size(75.dp), 200, RoundedCornerShape(0.dp)) }
                }
            } else {
                CoverArt(covers.firstOrNull(), Modifier.fillMaxSize(), 400, RoundedCornerShape(0.dp), iconSize = 48.dp)
            }
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("DISCOVER MIX", style = MaterialTheme.typography.labelMedium, color = accent)
            }
            Text("Songs you haven't heard yet", style = MaterialTheme.typography.headlineMedium)
            Text(
                buildString {
                    append(songsLabel(mix.size))
                    val secs = mix.sumOf { it.durationSec.toLong() }
                    if (secs > 0) append(" · ").append(formatLongDuration(secs))
                    val artists = mix.mapNotNull { it.artist }.distinct()
                    if (artists.isNotEmpty()) append(" · ").append(artists.take(3).joinToString()).append(if (artists.size > 3) " and more" else "")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay, enabled = mix.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                }
                OutlinedButton(onClick = onShuffle, enabled = mix.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                }
                OutlinedButton(onClick = onSave, enabled = mix.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save as playlist")
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(section: DiscoverSection, onPlay: (AlbumDto) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(section.albums, key = { it.id }) { a ->
            Column(
                Modifier.width(160.dp).clip(RoundedCornerShape(12.dp)).clickable { onPlay(a) }.pointerHoverIcon(PointerIcon.Hand).padding(4.dp),
            ) {
                CoverArt(a.coverArt, Modifier.size(152.dp), 400, RoundedCornerShape(10.dp), iconSize = 40.dp, seed = a.id)
                Spacer(Modifier.height(6.dp))
                Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    albumNote(a),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
