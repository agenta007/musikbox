package app.musikbox.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musikbox.desktop.player.RadioState
import app.musikbox.radio.RadioCatalog
import app.musikbox.radio.RadioCategory
import app.musikbox.radio.RadioDial
import app.musikbox.radio.RadioStation
import app.musikbox.radio.flagOf
import app.musikbox.radio.stationCount
import app.musikbox.radio.toggledFavorite
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

private val CARD_WIDTH = 300.dp

private enum class RadioTab(val label: String) { Favorites("Favourites"), Countries("Countries"), Genres("Genres") }

/** "🇧🇬 Bulgaria · Pop · MP3 128k" */
fun RadioCatalog.subtitle(s: RadioStation, withCountry: Boolean = true): String = listOfNotNull(
    if (withCountry) "${flagOf(s.countryCode)} ${countryName(s.countryCode)}" else null,
    s.genres.firstOrNull(),
    s.quality.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * Radio: the tuner on top (the playing list's stations as cards to drag, scroll or click through,
 * with the live waveform and an FM dial), and below it favourites, countries, genres and search.
 */
@Composable
fun RadioScreen() {
    val c = LocalContainer.current
    val catalog by c.radioStations.catalog.collectAsState()
    val settings by c.settings.state.collectAsState()
    val state by c.player.state.collectAsState()
    LaunchedEffect(Unit) { c.radioStations.ensureLoaded() }
    val home = settings.radioCountry ?: RadioCatalog.localeCountry()
    val homeLabel = if (settings.radioCountry != null) "Last opened" else "Where you are"
    val favorites = settings.radioFavorites
    var tab by remember { mutableStateOf(if (favorites.isEmpty()) RadioTab.Countries else RadioTab.Favorites) }
    var open by remember { mutableStateOf(RadioCategory.parse(settings.radioCategory).takeIf { it !is RadioCategory.Favorites }) }
    var query by remember { mutableStateOf("") }
    val toggle: (String) -> Unit = { id -> c.settings.update { it.copy(radioFavorites = toggledFavorite(it.radioFavorites, id)) } }
    val cat = catalog

    fun play(title: String, key: String, list: List<RadioStation>, index: Int) {
        val r = c.player.state.value.radio
        if (r != null && r.categoryKey == key && r.stations == list) c.player.tuneTo(index)
        else c.player.playRadio(title, key, list, index)
    }

    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        state = grid,
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        full {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Radio", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                SearchField(query, { query = it }, "Search ${cat?.stations?.size?.let { "%,d".format(it) } ?: ""} stations", Modifier.width(300.dp))
            }
        }
        full {
            val radio = state.radio
            if (radio != null && cat != null) Tuner(radio, state.isPlaying, state.buffering, state.error, cat, favorites, toggle)
            else Text(
                "Internet radio from ${cat?.countries?.size ?: 222} countries. Pick a station below to tune in; " +
                    "then drag the card, scroll, or use ← → to change station.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        if (cat == null) {
            full { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyVerticalGrid
        }

        val q = query.trim()
        if (q.isNotEmpty()) {
            val results = cat.search(q, home)
            full { Caption(if (results.isEmpty()) "No station is called that" else stationCount(results.size)) }
            stationItems(cat, results, "Search: $q", "search:$q", favorites, state.radio, state.isPlaying, toggle, ::play)
            return@LazyVerticalGrid
        }

        val category = open
        if (category != null) {
            val list = cat.stations(category, favorites, home)
            val title = cat.title(category)
            full {
                Row(Modifier.padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to ${tab.label.lowercase()}") }
                    if (category is RadioCategory.Country) Text(flagOf(category.code), fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Caption(stationCount(list.size))
                    TextButton(onClick = { play(title, category.key, list, 0) }) { Text("Tune in") }
                }
            }
            stationItems(cat, list, title, category.key, favorites, state.radio, state.isPlaying, toggle, ::play, withCountry = category !is RadioCategory.Country)
            return@LazyVerticalGrid
        }

        full {
            Row(Modifier.padding(top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioTab.entries.forEach { t ->
                    FilterChip(
                        selected = tab == t, onClick = { tab = t },
                        label = { Text(if (t == RadioTab.Favorites && favorites.isNotEmpty()) "${t.label} ${favorites.size}" else t.label) },
                        shape = RoundedCornerShape(50), border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        when (tab) {
            RadioTab.Favorites -> {
                val list = cat.stationsFor(favorites)
                if (list.isEmpty()) full {
                    Caption("No favourite stations yet. Click the heart on a station to keep it here.")
                } else stationItems(cat, list, "Favourites", RadioCategory.Favorites.key, favorites, state.radio, state.isPlaying, toggle, ::play)
            }
            RadioTab.Countries -> items(cat.countriesFor(home), key = { "c-" + it.code }) { country ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (country.code == home) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable { open = RadioCategory.Country(country.code); c.settings.update { it.copy(radioCategory = "country:${country.code}", radioCountry = country.code) } }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.flag, fontSize = 24.sp, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(country.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Caption(if (country.code == home) "$homeLabel · ${stationCount(country.stations)}" else stationCount(country.stations))
                    }
                }
            }
            RadioTab.Genres -> items(cat.genres, key = { "g-" + it.name }) { g ->
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).height(96.dp).clip(RoundedCornerShape(16.dp))
                        .background(placeholderBrush("genre " + g.name))
                        .clickable { open = RadioCategory.Genre(g.name); c.settings.update { it.copy(radioCategory = "genre:${g.name}") } }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(16.dp),
                ) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(stationCount(g.stations), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

private fun LazyGridScope.stationItems(
    cat: RadioCatalog,
    list: List<RadioStation>,
    title: String,
    key: String,
    favorites: List<String>,
    radio: RadioState?,
    playing: Boolean,
    toggle: (String) -> Unit,
    play: (String, String, List<RadioStation>, Int) -> Unit,
    withCountry: Boolean = true,
) = itemsIndexed(list, key = { _, s -> "s-" + s.id }) { i, s ->
    val current = radio?.station?.id == s.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable { play(title, key, list, i) }.pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            StationLogo(s.name, s.logo, Modifier.size(48.dp))
            if (current) Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                EqualizerBars(playing, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) Palette.Accent else MaterialTheme.colorScheme.onSurface)
            Caption(cat.subtitle(s, withCountry))
        }
        FavoriteButton(s.id in favorites, { toggle(s.id) }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The tuner. Cards don't follow the mouse by themselves on desktop, so dragging, the wheel and
 * the arrow keys move the pager; settling on a card tunes to it.
 */
@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
private fun Tuner(
    radio: RadioState,
    isPlaying: Boolean,
    buffering: Boolean,
    error: String?,
    cat: RadioCatalog,
    favorites: List<String>,
    toggle: (String) -> Unit,
) {
    val c = LocalContainer.current
    val stations = radio.stations
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = radio.index) { stations.size }
    val latest by rememberUpdatedState(radio)

    LaunchedEffect(pager, stations) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().debounce(350).collect { page ->
            if (page != latest.index) c.player.tuneTo(page)
        }
    }
    LaunchedEffect(radio.index, stations) {
        if (!pager.isScrollInProgress && pager.settledPage != radio.index) pager.animateScrollToPage(radio.index)
    }
    fun go(page: Int) { scope.launch { pager.animateScrollToPage(page.coerceIn(0, stations.lastIndex)) } }
    fun settle() = go(pager.currentPage + if (pager.currentPageOffsetFraction > 0.5f) 1 else if (pager.currentPageOffsetFraction < -0.5f) -1 else 0)

    val position = pager.currentPage + pager.currentPageOffsetFraction
    val shown = stations[pager.currentPage.coerceIn(0, stations.lastIndex)]
    val tint by rememberArtworkColor(shown.logo, Palette.Accent)
    val accent = lerp(tint, Palette.Accent, 0.35f)
    var wheelAt by remember { mutableLongStateOf(0L) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 14.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.55f), lerp(tint, Color.Black, 0.85f))))
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(radio.title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.65f))
        Row(Modifier.fillMaxWidth().height(330.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(pager.currentPage - 1) }, enabled = pager.currentPage > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous station", Modifier.size(34.dp)) }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = false,
                pageSize = androidx.compose.foundation.pager.PageSize.Fixed(CARD_WIDTH),
                pageSpacing = 18.dp,
                beyondViewportPageCount = 2,
                key = { stations[it].id },
                // the station tuned in sits in the middle, its neighbours either side
                contentPadding = PaddingValues(horizontal = ((maxWidth - CARD_WIDTH) / 2).coerceAtLeast(0.dp)),
                modifier = Modifier.fillMaxSize()
                    .pointerInput(stations) {
                        detectHorizontalDragGestures(onDragEnd = { settle() }, onDragCancel = { settle() }) { change, dx ->
                            change.consume(); pager.dispatchRawDelta(-dx)
                        }
                    }
                    .onPointerEvent(PointerEventType.Scroll) { e ->
                        val d = e.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                        e.changes.forEach { it.consume() } // the wheel turns the dial, not the page
                        val v = if (abs(d.x) > abs(d.y)) d.x else d.y
                        val now = System.currentTimeMillis()
                        if (v != 0f && now - wheelAt > 180) { wheelAt = now; go(pager.currentPage + v.sign.toInt()) }
                    },
            ) { page ->
                val s = stations[page]
                val isOn = s.id == radio.station?.id
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                StationCard(
                    s, cat.subtitle(s),
                    nowPlaying = if (isOn) radio.streamTitle else null,
                    error = if (isOn) error else null,
                    buffering = isOn && buffering,
                    playing = isOn && isPlaying,
                    static = (abs(pager.currentPageOffsetFraction) * 2).coerceIn(0f, 1f),
                    tint = accent,
                    favorite = s.id in favorites,
                    onToggleFavorite = { toggle(s.id) },
                    onPlay = { if (isOn) c.player.togglePlay() else { go(page); c.player.tuneTo(page) } },
                    modifier = Modifier.graphicsLayer {
                        val f = abs(offset).coerceIn(0f, 1.5f)
                        scaleX = 1f - 0.1f * f; scaleY = 1f - 0.1f * f
                        alpha = 1f - 0.4f * f.coerceAtMost(1f)
                    },
                )
            }
            }
            IconButton(onClick = { go(pager.currentPage + 1) }, enabled = pager.currentPage < stations.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Next station", Modifier.size(34.dp)) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                RadioDial.label(position.roundToInt().coerceIn(0, stations.lastIndex), stations.size),
                fontSize = 34.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, color = Color.White,
            )
            Text(" FM", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(16.dp))
            Text("Station ${pager.currentPage + 1} of ${stations.size}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(bottom = 8.dp))
        }
        TunerDial(
            position = position, count = stations.size, tint = accent,
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 24.dp),
            onDrag = { moved -> pager.dispatchRawDelta(moved * (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing)) },
            onDragEnd = { settle() },
            onTap = { go(it) },
        )
    }
}

@Composable
private fun StationCard(
    s: RadioStation,
    subtitle: String,
    nowPlaying: String?,
    error: String?,
    buffering: Boolean,
    playing: Boolean,
    static: Float,
    tint: Color,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glow by animateFloatAsState(if (playing) 1f else 0f, label = "cardGlow")
    Column(
        modifier.fillMaxSize().padding(vertical = 8.dp)
            .shadow((8 + 12 * glow).dp, RoundedCornerShape(24.dp), spotColor = tint, ambientColor = tint)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF24242E), Color(0xFF16161D))))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            StationLogo(s.name, s.logo, Modifier.fillMaxHeight(0.92f).aspectRatio(1f), RoundedCornerShape(18.dp), textSize = 34)
            Row(Modifier.align(Alignment.TopEnd)) { FavoriteButton(favorite, onToggleFavorite) }
            Box(
                Modifier.align(Alignment.BottomEnd).size(44.dp).clip(CircleShape).background(Color.White)
                    .clickable(onClick = onPlay).pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.Black)
            }
        }
        LiveWave(playing, tint, Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp), static = static)
        Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = when {
            error != null -> "$error. Try another."
            buffering -> "Tuning in…"
            nowPlaying != null -> nowPlaying
            playing -> "On air"
            else -> " "
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playing && error == null) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Danger))
                Spacer(Modifier.width(6.dp))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (error != null) Palette.Danger else Color.White.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.basicMarquee())
        }
    }
}

/** The station's logo on a light tile, or its initials on a colour of its own. */
@Composable
fun StationLogo(name: String, logo: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp), textSize: Int = 16) {
    val fallback = @Composable {
        Box(Modifier.fillMaxSize().background(placeholderBrush(name)), contentAlignment = Alignment.Center) {
            Text(initials(name), color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = textSize.sp)
        }
    }
    Box(modifier.clip(shape)) {
        if (logo == null) fallback() else SubcomposeAsyncImage(
            model = logo, contentDescription = null, contentScale = ContentScale.Fit,
            loading = { fallback() }, error = { fallback() },
            success = { st ->
                Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F5)).padding(5.dp), contentAlignment = Alignment.Center) {
                    Image(st.painter, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun initials(name: String): String =
    name.split(' ', '-', '_', '.').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "FM" }

@Composable
fun FavoriteButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.White) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (on) "Remove from favourites" else "Add to favourites",
            tint = if (on) Palette.Accent else tint.copy(alpha = 0.8f), modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The station's live waveform from [app.musikbox.desktop.player.RadioPcm], looked up by the
 * playhead and interpolated between its updates. Draws frames only while it moves (playing, or
 * tuning static) and the window is visible; otherwise a still line.
 */
@Composable
fun LiveWave(playing: Boolean, tint: Color, modifier: Modifier = Modifier, static: Float = 0f) {
    val c = LocalContainer.current
    val motion = LocalMotion.current
    val position by rememberPlayhead()
    var anchorPos by remember { mutableLongStateOf(0L) }
    var anchorAt by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(position) { anchorPos = position; anchorAt = System.nanoTime() }
    val samples = remember { FloatArray(1024) }
    val shown = remember { FloatArray(140) }
    val isPlaying by rememberUpdatedState(playing)
    val noise by rememberUpdatedState(static)
    var frame by remember { mutableIntStateOf(0) }
    val moving = (playing || static > 0f) && motion

    LaunchedEffect(moving) {
        if (!moving) { shown.fill(0f); frame++; return@LaunchedEffect }
        while (true) withFrameNanos { now ->
            val pos = anchorPos + if (isPlaying) (now - anchorAt) / 1_000_000 else 0
            val fresh = isPlaying && c.player.radioPcm.window(pos, samples)
            val step = samples.size / shown.size
            for (i in shown.indices) {
                var v = 0f
                if (fresh) for (j in i * step until (i + 1) * step) if (abs(samples[j]) > abs(v)) v = samples[j]
                val target = (if (fresh) v * 1.6f else 0f) * (1 - noise) + (Random.nextFloat() * 2 - 1) * 0.55f * noise
                shown[i] += (target - shown[i]) * 0.55f
            }
            frame++
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val mid = size.height / 2
        val amp = size.height / 2 * 0.92f
        val path = Path()
        for (i in shown.indices) {
            val x = size.width * i / (shown.size - 1)
            val y = mid - shown[i].coerceIn(-1f, 1f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val brush = Brush.horizontalGradient(listOf(tint.copy(alpha = 0f), lerp(tint, Color.White, 0.4f), Color.White, lerp(tint, Color.White, 0.4f), tint.copy(alpha = 0f)))
        drawPath(path, brush, alpha = 0.25f, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, brush, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** An FM dial: a tick per station, frequencies under some, a fixed needle; drag or click to tune. */
@Composable
fun TunerDial(
    position: Float,
    count: Int,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Accent,
    spacing: Dp = 22.dp,
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onTap: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val labelColor = Palette.TextMid
    val pos by rememberUpdatedState(position)
    Canvas(
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(count) {
                detectHorizontalDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, dx ->
                    change.consume(); onDrag(-dx / spacing.toPx())
                }
            }
            .pointerInput(count) {
                detectTapGestures { o -> onTap((pos + (o.x - size.width / 2f) / spacing.toPx()).roundToInt().coerceIn(0, count - 1)) }
            },
    ) {
        val step = spacing.toPx()
        val cx = size.width / 2
        val base = size.height * 0.6f
        val first = max(0, floor(pos - cx / step).toInt() - 1)
        val last = minOf(count - 1, (pos + cx / step).toInt() + 1)
        val labelEvery = max(1, (58.dp.toPx() / step).toInt() + 1)
        for (i in first..last) {
            val x = cx + (i - pos) * step
            val edge = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val major = i % labelEvery == 0
            val h = if (major) size.height * 0.34f else size.height * 0.18f
            drawLine(labelColor.copy(alpha = 0.25f + 0.55f * edge), Offset(x, base - h), Offset(x, base), strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
            if (major) {
                val text = measurer.measure(RadioDial.label(i, count), TextStyle(color = labelColor.copy(alpha = 0.3f + 0.7f * edge), fontSize = 11.sp, fontWeight = FontWeight.Medium))
                drawText(text, topLeft = Offset(x - text.size.width / 2f, base + 4.dp.toPx()))
            }
        }
        drawLine(labelColor.copy(alpha = 0.35f), Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        drawLine(tint.copy(alpha = 0.3f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Three bouncing bars: this station is on. Still when paused or the window is hidden. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    val motion = LocalMotion.current
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(playing, motion) {
        if (!playing || !motion) return@LaunchedEffect
        while (true) withFrameNanos { t = it / 1e9f }
    }
    Canvas(modifier) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val h = if (playing) 0.35f + 0.65f * abs(sin(t * (3.1f + i * 1.3f) + i * PI.toFloat() / 3)) else 0.3f
            drawRoundRect(color, topLeft = Offset(w * i * 2, size.height * (1 - h)), size = Size(w, size.height * h), cornerRadius = CornerRadius(w / 2))
        }
    }
}
