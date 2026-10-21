package app.musikbox.ui.discover

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.data.local.SongEntity
import app.musikbox.data.remote.AlbumDto
import app.musikbox.data.toEntity
import app.musikbox.discover.albumNote
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.LocalSnackbar
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.components.SongRow
import app.musikbox.ui.components.formatLongDuration
import app.musikbox.ui.components.rememberSongActions
import app.musikbox.ui.components.songsLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiscoverScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val ui by container.discover.state.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val downloaded by container.offline.downloaded.collectAsStateWithLifecycle()
    val actions = rememberSongActions()
    LaunchedEffect(Unit) { container.discover.ensureFresh() }

    val result = ui.result
    val mix = remember(result) { result?.mix?.map { it.toEntity() }.orEmpty() }

    fun playAlbum(a: AlbumDto) = container.scope.launch {
        runCatching { container.api.getAlbum(a.id).song.map { it.toEntity() } }
            .onSuccess { container.player.playSongs(it, 0) }
            .onFailure { snackbar.showSnackbar("Couldn't load ${a.name}") }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Discover", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = container.discover::regenerate, enabled = !ui.loading) { Icon(Icons.Rounded.Refresh, "New picks") }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(2.dp)))
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Text(
                    "New to you — picked from your own library by what you play and star.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                ui.error?.let {
                    Text("Couldn't build Discover: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                }
            }
            if (result != null) {
                item {
                    MixHero(
                        songs = mix,
                        onPlay = { container.player.playSongs(mix, 0, shuffle = false) },
                        onShuffle = { container.player.playSongs(mix, 0, shuffle = true) },
                        onSave = {
                            container.scope.launch {
                                val name = "Discover · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                                val r = container.library.createPlaylist(name, mix.map { it.id })
                                snackbar.showSnackbar(if (r.isSuccess) "Saved as “$name”" else "Couldn't save the playlist")
                            }
                        },
                    )
                    val taste = result.profile.topGenres.take(3) + result.profile.topArtists.take(2).map { it.second }.filter { it.isNotBlank() }
                    Text(
                        if (taste.isEmpty()) "Play and star some music for sharper picks — for now, a wide sample."
                        else "Based on " + taste.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(mix, key = { i, s -> "mix-$i-${s.id}" }) { i, song ->
                    SongRow(
                        song = song, index = i + 1,
                        onClick = { container.player.playSongs(mix, i) },
                        actions = actions,
                        isCurrent = player.song?.id == song.id, isPlaying = player.isPlaying,
                        downloaded = song.id in downloaded,
                    )
                }
                result.sections.forEach { section ->
                    item(key = section.key) {
                        Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp))
                        Text(
                            section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (section.albums.isNotEmpty()) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(section.albums, key = { it.id }) { a -> AlbumCard(a) { playAlbum(a) } }
                            }
                        }
                    }
                    if (section.songs.isNotEmpty()) {
                        val songs = section.songs.map { it.toEntity() }
                        itemsIndexed(songs, key = { i, s -> "${section.key}-$i-${s.id}" }) { i, song ->
                            SongRow(
                                song = song,
                                onClick = { container.player.playSongs(songs, i) },
                                actions = actions,
                                isCurrent = player.song?.id == song.id, isPlaying = player.isPlaying,
                                downloaded = song.id in downloaded,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixHero(songs: List<SongEntity>, onPlay: () -> Unit, onShuffle: () -> Unit, onSave: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val covers = songs.map { it.coverArt }.distinct().take(4)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.5f), MaterialTheme.colorScheme.surfaceContainerHigh)))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp))) {
                if (covers.size >= 4) {
                    Column {
                        Row { CoverArt(covers[0], Modifier.size(52.dp), 160, RoundedCornerShape(0.dp)); CoverArt(covers[1], Modifier.size(52.dp), 160, RoundedCornerShape(0.dp)) }
                        Row { CoverArt(covers[2], Modifier.size(52.dp), 160, RoundedCornerShape(0.dp)); CoverArt(covers[3], Modifier.size(52.dp), 160, RoundedCornerShape(0.dp)) }
                    }
                } else {
                    CoverArt(covers.firstOrNull(), Modifier.fillMaxSize(), 300, RoundedCornerShape(0.dp), iconSize = 40.dp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DISCOVER MIX", style = MaterialTheme.typography.labelMedium, color = accent)
                }
                Text("Songs you haven't heard yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append(songsLabel(songs.size))
                        val secs = songs.sumOf { it.durationSec.toLong() }
                        if (secs > 0) append(" · ").append(formatLongDuration(secs))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(accent).clickable(enabled = songs.isNotEmpty(), onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onShuffle, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle") }
            IconButton(onClick = onSave, enabled = songs.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist") }
            Text("Save as playlist", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onSave))
        }
    }
}

@Composable
private fun AlbumCard(a: AlbumDto, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        CoverArt(a.coverArt, Modifier.size(148.dp), 400, RoundedCornerShape(12.dp), iconSize = 36.dp, seed = a.id)
        Spacer(Modifier.height(6.dp))
        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(albumNote(a), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
