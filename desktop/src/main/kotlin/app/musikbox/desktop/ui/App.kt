package app.musikbox.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import app.musikbox.desktop.DesktopContainer

class AppUiState {
    var queueOpen by mutableStateOf(false)
}

@Composable
fun MusikboxApp(container: DesktopContainer, nav: Navigator, ui: AppUiState, snackbar: SnackbarHostState) {
    val settings by container.settings.state.collectAsState()
    MusikboxTheme(settings.pureBlack) {
        CompositionLocalProvider(LocalContainer provides container, LocalNav provides nav, LocalSnackbar provides snackbar) {
            Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                if (settings.credentials == null) {
                    LaunchedEffect(Unit) { nav.reset() }
                    LoginScreen()
                } else {
                    MainLayout(ui, snackbar)
                }
            }
        }
    }
}

@Composable
private fun MainLayout(ui: AppUiState, snackbar: SnackbarHostState) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val player by c.player.state.collectAsState()
    LaunchedEffect(Unit) { c.library.syncIfStale() }
    LaunchedEffect(player.error) {
        player.error?.let { snackbar.showSnackbar(it); c.player.dismissError() }
    }

    val focusManager = LocalFocusManager.current
    // Clicking empty space drops focus (e.g. out of a search box) so Space plays/pauses again.
    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        Row(Modifier.weight(1f)) {
            Sidebar(Modifier.width(272.dp).fillMaxHeight())
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(nav.current, animationSpec = tween(180)) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen()
                        is Screen.Playlist -> PlaylistScreen(screen.id)
                        is Screen.Search -> SearchScreen(screen.query)
                        Screen.Downloads -> DownloadsScreen()
                        Screen.Discover -> DiscoverScreen()
                        Screen.Devices -> DevicesScreen()
                        Screen.Settings -> SettingsScreen()
                        Screen.NowPlaying -> NowPlayingScreen()
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
                    Snackbar(
                        data, shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            AnimatedVisibility(ui.queueOpen, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                Row {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    QueuePanel(onClose = { ui.queueOpen = false }, modifier = Modifier.width(340.dp).fillMaxHeight())
                }
            }
        }
        PlayerBar(queueOpen = ui.queueOpen, onToggleQueue = { ui.queueOpen = !ui.queueOpen })
    }
}
