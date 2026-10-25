package app.musikbox.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.musikbox.playback.SleepTimer
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.LocalSnackbar
import app.musikbox.ui.components.AddToPlaylistSheet
import app.musikbox.ui.components.CoverArt
import app.musikbox.ui.components.SeekBar
import app.musikbox.ui.components.formatTrackTime
import app.musikbox.ui.components.rememberArtworkColor
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.runtime.saveable.rememberSaveable
import app.musikbox.visualizer.VisualizerStyle
import androidx.compose.foundation.layout.fillMaxHeight

@Composable
fun NowPlayingScreen(onCollapse: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val container = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val sleep by player.sleepTimer.collectAsStateWithLifecycle()
    val appSettings by container.settings.settings.collectAsStateWithLifecycle()
    val vizStyle = VisualizerStyle.entries.firstOrNull { it.name == appSettings.visualizerStyle } ?: VisualizerStyle.Bars
    val song = state.song
    if (song == null) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }
    val songFlow = remember(song.id) { container.library.song(song.id) }
    val storedSong by songFlow.collectAsStateWithLifecycle(null)
    var starOverride by remember(song.id) { mutableStateOf<Boolean?>(null) }
    val starred = starOverride ?: storedSong?.starred ?: song.starred
    val sourceFlow = remember(state.sourcePlaylistId) {
        state.sourcePlaylistId?.let { container.library.playlist(it) } ?: flowOf(null)
    }
    val sourceName = sourceFlow.collectAsStateWithLifecycle(null).value?.name

    val accent = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val tint by rememberArtworkColor(state.artworkUrl, accent)

    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    LaunchedEffect(song.id) {
        while (true) {
            position = player.positionMs
            buffered = player.bufferedMs
            delay(250)
        }
    }

    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    // Pull down anywhere to collapse.
    val density = LocalDensity.current
    val collapseThreshold = with(density) { 140.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    val animY by animateFloatAsState(dragY, spring(stiffness = 600f), label = "collapseDrag")

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY > collapseThreshold) onCollapse() else dragY = 0f },
                    onDragCancel = { dragY = 0f },
                ) { change, amount ->
                    change.consume()
                    dragY = (dragY + amount).coerceAtLeast(0f)
                }
            }
            .offset { IntOffset(0, animY.roundToInt()) },
    ) {
        AsyncImage(
            model = state.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(100.dp).graphicsLayer { alpha = 0.5f },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.45f), bg.copy(alpha = 0.72f), bg.copy(alpha = 0.96f))),
            ),
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Collapse", modifier = Modifier.size(32.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = state.sourcePlaylistId != null) {
                            state.sourcePlaylistId?.let(onOpenPlaylist)
                        }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (sourceName != null) "PLAYING FROM PLAYLIST" else "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    if (sourceName != null) {
                        Text(sourceName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { showQueue = true }, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue")
                }
            }

            // Artwork — breathes with play state, swipe to skip.
            val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.86f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "artScale")
            val swipeThreshold = with(density) { 80.dp.toPx() }
            var swipeX by remember { mutableFloatStateOf(0f) }
            val animX by animateFloatAsState(swipeX, label = "artSwipe")
            if (showLyrics) {
                LyricsView(
                    song, position, tint,
                    onSeek = { player.seekTo(it); position = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (vizStyle == VisualizerStyle.Radial) {
                    SpectrumVisualizer(vizStyle, tint, state.isPlaying, Modifier.fillMaxSize())
                }
                val radial = vizStyle == VisualizerStyle.Radial
                AnimatedContent(
                    targetState = song,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it.id },
                    label = "artwork",
                ) { s ->
                    CoverArt(
                        s.coverArt,
                        Modifier
                            .padding(vertical = 16.dp)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .graphicsLayer {
                                val s = if (radial) scale * 0.6f else scale
                                scaleX = s; scaleY = s
                                translationX = animX * 0.5f
                                rotationZ = animX / 60f
                            }
                            .shadow(36.dp, if (radial) CircleShape else RoundedCornerShape(18.dp), ambientColor = tint, spotColor = tint)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (swipeX < -swipeThreshold) player.next() else if (swipeX > swipeThreshold) player.previous()
                                        swipeX = 0f
                                    },
                                    onDragCancel = { swipeX = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    swipeX += amount
                                }
                            },
                        sizePx = 1000,
                        shape = if (radial) CircleShape else RoundedCornerShape(18.dp),
                        iconSize = 72.dp,
                        seed = s.albumId,
                    )
                }
                if (vizStyle == VisualizerStyle.Bars || vizStyle == VisualizerStyle.Mirror) {
                    SpectrumVisualizer(
                        vizStyle, tint, state.isPlaying,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (vizStyle == VisualizerStyle.Mirror) 0.5f else 0.36f),
                    )
                }
            }

            // Title / artist / star
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                    )
                    Text(
                        listOfNotNull(song.artist, song.album).joinToString(" — "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    val s = (storedSong ?: song).copy(starred = starred)
                    starOverride = !starred
                    container.scope.launch {
                        container.library.toggleStar(s).onFailure {
                            starOverride = starred
                            snackbar.showSnackbar("Couldn't reach the server")
                        }
                    }
                }) {
                    Icon(
                        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (starred) "Unstar" else "Star",
                        tint = if (starred) tint else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SeekBar(
                positionMs = position,
                durationMs = state.durationMs,
                bufferedMs = buffered,
                onSeek = { player.seekTo(it); position = it },
                color = Color.White,
            )

            // Transport
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(Icons.Rounded.Shuffle, "Shuffle", state.shuffle, tint, player::toggleShuffle)
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(40.dp))
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .shadow(20.dp, CircleShape, spotColor = tint)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = player::togglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                IconButton(onClick = player::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Rounded.SkipNext, "Next",
                        modifier = Modifier.size(40.dp),
                        tint = Color.White.copy(alpha = if (state.hasNext) 1f else 0.35f),
                    )
                }
                ToggleIcon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat",
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                    tint,
                    player::cycleRepeat,
                )
            }

            // Secondary actions
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SleepButton(sleep, tint) { showSleep = true }
                TextButton(onClick = {
                    val next = VisualizerStyle.entries[(vizStyle.ordinal + 1) % VisualizerStyle.entries.size]
                    container.scope.launch { container.settings.setVisualizerStyle(next.name) }
                }) {
                    Icon(
                        Icons.Rounded.GraphicEq, "Visualizer",
                        tint = if (vizStyle != VisualizerStyle.Off) tint else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp),
                    )
                    if (vizStyle != VisualizerStyle.Off) {
                        Spacer(Modifier.width(6.dp))
                        Text(vizStyle.label, color = tint, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        Icons.Rounded.Lyrics, if (showLyrics) "Hide lyrics" else "Lyrics",
                        tint = if (showLyrics) tint else Color.White.copy(alpha = 0.85f),
                    )
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = Color.White.copy(alpha = 0.85f))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepTimerSheet(onDismiss = { showSleep = false })
    if (showAdd) AddToPlaylistSheet(listOf(storedSong ?: song)) { showAdd = false }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) tint else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
            }
        }
    }
}

@Composable
private fun SleepButton(timer: SleepTimer?, tint: Color, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer is SleepTimer.At) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    TextButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Bedtime, "Sleep timer",
            tint = if (timer != null) tint else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp),
        )
        when (timer) {
            is SleepTimer.At -> {
                Spacer(Modifier.width(6.dp))
                Text(formatTrackTime(timer.endsAtMs - now), color = tint, style = MaterialTheme.typography.labelLarge)
            }
            SleepTimer.EndOfTrack -> {
                Spacer(Modifier.width(6.dp))
                Text("End of track", color = tint, style = MaterialTheme.typography.labelLarge)
            }
            null -> Unit
        }
    }
}
