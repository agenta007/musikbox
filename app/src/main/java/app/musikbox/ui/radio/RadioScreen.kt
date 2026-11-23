package app.musikbox.ui.radio

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.playback.RadioSession
import app.musikbox.radio.RadioCatalog
import app.musikbox.radio.RadioCategory
import app.musikbox.radio.RadioStation
import app.musikbox.radio.stationCount
import app.musikbox.radio.toggledFavorite
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalBottomInset
import app.musikbox.ui.components.placeholderBrush
import kotlinx.coroutines.launch

private enum class RadioTab(val label: String) { Favorites("Favourites"), Countries("Countries"), Genres("Genres") }

/** Plays [stations] from [index] as the radio list [title] and opens the tuner. */
@Composable
fun rememberRadioPlayer(onOpenPlayer: () -> Unit): (RadioCatalog, String, String, List<RadioStation>, Int) -> Unit {
    val container = LocalAppContainer.current
    return remember(container) {
        { catalog, title, key, stations, index ->
            container.player.playRadio(RadioSession(title, key, stations), index) { catalog.subtitle(it) }
            onOpenPlayer()
        }
    }
}

/** Favourite stations; every country's; genres; and a search over all 32,000. */
@Composable
fun RadioScreen(onBack: () -> Unit, onOpenCategory: (String) -> Unit, onOpenPlayer: () -> Unit) {
    val container = LocalAppContainer.current
    val catalog by container.radioStations.catalog.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.radioStations.ensureLoaded() }
    val favorites = settings.radioFavorites
    val home = settings.radioCountry ?: RadioCatalog.localeCountry()
    val homeLabel = if (settings.radioCountry != null) "Last opened" else "Where you are"
    var tab by rememberSaveable { mutableStateOf(if (favorites.isEmpty()) RadioTab.Countries else RadioTab.Favorites) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val play = rememberRadioPlayer(onOpenPlayer)
    val toggle: (String) -> Unit = { id -> container.scope.launch { container.settings.setRadioFavorites(toggledFavorite(container.settings.current.radioFavorites, id)) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            if (searching) {
                TextField(
                    query, { query = it },
                    placeholder = { Text("Search stations") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            } else {
                Text("Radio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search stations") }
            }
        }

        val cat = catalog
        if (cat == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val bottom = PaddingValues(bottom = LocalBottomInset.current + 24.dp)

        if (searching && query.isNotBlank()) {
            val results = remember(cat, query) { cat.search(query, home) }
            LazyColumn(contentPadding = bottom) {
                item {
                    Text(
                        if (results.isEmpty()) "No station is called that" else stationCount(results.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(results, key = { _, s -> s.id }) { i, s ->
                    StationRow(
                        s, cat.subtitle(s), s.id in favorites, player.radioId == s.id, player.isPlaying,
                        onClick = { play(cat, "Search: ${query.trim()}", "search:${query.trim()}", results, i) },
                        onToggleFavorite = { toggle(s.id) },
                    )
                }
            }
            return@Column
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(if (t == RadioTab.Favorites && favorites.isNotEmpty()) "${t.label} ${favorites.size}" else t.label) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    border = null,
                )
            }
        }

        when (tab) {
            RadioTab.Favorites -> {
                val stations = remember(cat, favorites) { cat.stationsFor(favorites) }
                if (stations.isEmpty()) {
                    EmptyFavorites()
                } else LazyColumn(contentPadding = bottom) {
                    item {
                        PlayAllRow(stationCount(stations.size)) { play(cat, "Favourites", RadioCategory.Favorites.key, stations, 0) }
                    }
                    itemsIndexed(stations, key = { _, s -> s.id }) { i, s ->
                        StationRow(
                            s, cat.subtitle(s), true, player.radioId == s.id, player.isPlaying,
                            onClick = { play(cat, "Favourites", RadioCategory.Favorites.key, stations, i) },
                            onToggleFavorite = { toggle(s.id) },
                        )
                    }
                }
            }
            RadioTab.Countries -> LazyColumn(contentPadding = bottom) {
                val countries = cat.countriesFor(home)
                items(countries, key = { it.code }) { c ->
                    if (c.code == home) Text(
                        homeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCategory(RadioCategory.Country(c.code).key) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.flag, fontSize = 28.sp, modifier = Modifier.width(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stationCount(c.stations), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (c.code == home) Text(
                        "All countries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                    )
                }
            }
            RadioTab.Genres -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cat.genres, key = { it.name }) { g ->
                    Box(
                        Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(16.dp)).background(placeholderBrush("genre " + g.name))
                            .clickable { onOpenCategory(RadioCategory.Genre(g.name).key) }.padding(14.dp),
                    ) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.TopStart))
                        Text(stationCount(g.stations), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFavorites() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No favourite stations yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the heart on a station to keep it here. Browse Countries or Genres to find some.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayAllRow(count: String, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Button(onClick = onPlay, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Tune in")
        }
    }
}

/** One country's or genre's stations. */
@Composable
fun RadioListScreen(categoryKey: String, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val container = LocalAppContainer.current
    val catalog by container.radioStations.catalog.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    LaunchedEffect(categoryKey) {
        container.radioStations.ensureLoaded()
        container.settings.setRadioCategory(categoryKey)
    }
    val category = remember(categoryKey) { RadioCategory.parse(categoryKey) ?: RadioCategory.Favorites }
    val home = settings.radioCountry ?: RadioCatalog.localeCountry()
    val play = rememberRadioPlayer(onOpenPlayer)
    val favorites = settings.radioFavorites

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val cat = catalog
        val title = cat?.title(category) ?: ""
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            if (category is RadioCategory.Country) Text(app.musikbox.radio.flagOf(category.code), fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (cat == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val stations = remember(cat, category, favorites.takeIf { category == RadioCategory.Favorites }) { cat.stations(category, favorites, home) }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item { PlayAllRow(stationCount(stations.size)) { play(cat, title, categoryKey, stations, 0) } }
            itemsIndexed(stations, key = { _, s -> s.id }) { i, s ->
                StationRow(
                    s, cat.subtitle(s, withCountry = category !is RadioCategory.Country), s.id in favorites,
                    player.radioId == s.id, player.isPlaying,
                    onClick = { play(cat, title, categoryKey, stations, i) },
                    onToggleFavorite = {
                        container.scope.launch { container.settings.setRadioFavorites(toggledFavorite(container.settings.current.radioFavorites, s.id)) }
                    },
                )
            }
        }
    }
}
