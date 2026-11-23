package app.musikbox.ui.radio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.radio.RadioDial
import app.musikbox.radio.toggledFavorite
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.components.rememberArtworkColor
import app.musikbox.ui.theme.Palette
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tuner: the playing list's stations as cards to swipe through, the station's live waveform
 * across its card, and an FM dial under it that turns as you swipe (or drag the dial itself).
 * Settling on a card tunes to it.
 */
@OptIn(FlowPreview::class)
@Composable
fun RadioPlayerScreen(onCollapse: () -> Unit) {
    val container = LocalAppContainer.current
    val player = container.player
    val session by player.radio.collectAsStateWithLifecycle()
    val state by player.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val catalog by container.radioStations.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val radio = session
    if (radio == null || radio.stations.isEmpty()) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }
    val stations = radio.stations
    val playingIndex = stations.indexOfFirst { it.id == state.radioId }.takeIf { it >= 0 } ?: state.currentIndex.coerceIn(0, stations.lastIndex)
    val pager = rememberPagerState(initialPage = playingIndex) { stations.size }

    // Settling on a card tunes to it, once the swiping has stopped for a moment.
    LaunchedEffect(pager, stations) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().debounce(350).collect { page ->
            if (stations.getOrNull(page)?.id != player.state.value.radioId) player.tuneTo(page)
        }
    }
    // The player moved on its own (notification, headphones): follow it.
    LaunchedEffect(playingIndex) {
        if (!pager.isScrollInProgress && pager.settledPage != playingIndex) pager.animateScrollToPage(playingIndex)
    }

    val position = pager.currentPage + pager.currentPageOffsetFraction
    val shown = stations[pager.currentPage.coerceIn(0, stations.lastIndex)]
    val tint by rememberArtworkColor(shown.logo, Palette.Accent)
    val accent = lerp(tint, Palette.Accent, 0.35f)
    val favorites = settings.radioFavorites

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.45f), lerp(tint, Color.Black, 0.82f), MaterialTheme.colorScheme.background)),
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Close the tuner", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RADIO", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), letterSpacing = 2.sp)
                    Text(radio.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FavoriteButton(shown.id in favorites, {
                    scope.launch { container.settings.setRadioFavorites(toggledFavorite(container.settings.current.radioFavorites, shown.id)) }
                })
            }

            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 36.dp),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                key = { stations[it].id },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                val s = stations[page]
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val isOn = s.id == state.radioId
                StationCard(
                    name = s.name,
                    logo = s.logo,
                    subtitle = catalog?.subtitle(s).orEmpty(),
                    nowPlaying = if (isOn) state.streamTitle else null,
                    error = if (isOn) state.error else null,
                    buffering = isOn && state.isBuffering,
                    playing = isOn && state.isPlaying,
                    static = (abs(pager.currentPageOffsetFraction) * 2).coerceIn(0f, 1f),
                    tint = accent,
                    modifier = Modifier.graphicsLayer {
                        val f = abs(offset).coerceIn(0f, 1f)
                        scaleX = 1f - 0.08f * f; scaleY = 1f - 0.08f * f
                        alpha = 1f - 0.45f * f
                    },
                )
            }

            // The frequency and the dial
            val count = stations.size
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(
                    RadioDial.label(position.roundToInt().coerceIn(0, count - 1), count),
                    fontSize = 40.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, color = Color.White,
                )
                Text(" FM", fontSize = 16.sp, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                "Station ${pager.currentPage + 1} of $count",
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            TunerDial(
                position = position,
                count = count,
                tint = accent,
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp),
                onDrag = { stationsMoved ->
                    val page = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
                    pager.dispatchRawDelta(stationsMoved * page)
                },
                onDragEnd = { scope.launch { pager.animateScrollToPage(pager.currentPage + if (pager.currentPageOffsetFraction > 0.5f) 1 else 0) } },
                onTap = { scope.launch { pager.animateScrollToPage(it) } },
            )

            // Controls
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }, enabled = pager.currentPage > 0) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous station", modifier = Modifier.size(34.dp))
                }
                Box(
                    Modifier.size(72.dp).shadow(16.dp, CircleShape, spotColor = accent).clip(CircleShape).background(Color.White)
                        .clickable {
                            if (shown.id == state.radioId) player.togglePlayPause() else player.tuneTo(pager.currentPage)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val on = shown.id == state.radioId && state.isPlaying
                    if (shown.id == state.radioId && state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (on) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(38.dp))
                    }
                }
                IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(count - 1)) } }, enabled = pager.currentPage < count - 1) {
                    Icon(Icons.Rounded.SkipNext, "Next station", modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun StationCard(
    name: String,
    logo: String?,
    subtitle: String,
    nowPlaying: String?,
    error: String?,
    buffering: Boolean,
    playing: Boolean,
    static: Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val glow by animateFloatAsState(if (playing) 1f else 0f, label = "cardGlow")
    Column(
        modifier.fillMaxSize().padding(vertical = 8.dp)
            .shadow((10 + 14 * glow).dp, RoundedCornerShape(28.dp), spotColor = tint, ambientColor = tint)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF22222C), Color(0xFF15151C))))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            StationLogo(name, logo, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), RoundedCornerShape(22.dp), textSize = 40)
        }
        // The station's wave: live while it plays, static while the dial is between stations.
        LiveWave(
            playing = playing, tint = tint, static = static, live = playing || static > 0f,
            modifier = Modifier.fillMaxWidth().height(70.dp).padding(vertical = 6.dp),
        )
        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val line = when {
            error != null -> "$error. Swipe for another."
            buffering -> "Tuning in…"
            nowPlaying != null -> nowPlaying
            playing -> "On air"
            else -> " "
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playing && error == null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Danger))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) Palette.Danger else Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}
