package app.musikbox.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.musikbox.AppContainer
import app.musikbox.MusikboxApp
import app.musikbox.ui.components.MiniPlayer
import app.musikbox.ui.components.MiniPlayerInset
import app.musikbox.ui.devices.DevicesScreen
import app.musikbox.ui.discover.DiscoverScreen
import app.musikbox.ui.downloads.DownloadsScreen
import app.musikbox.ui.home.HomeScreen
import app.musikbox.ui.login.LoginScreen
import app.musikbox.ui.player.NowPlayingScreen
import app.musikbox.ui.playlist.PlaylistScreen
import app.musikbox.ui.search.SearchScreen
import app.musikbox.ui.settings.SettingsScreen
import app.musikbox.ui.theme.MusikboxTheme
import kotlinx.serialization.Serializable

@Serializable object LoginRoute
@Serializable object HomeRoute
@Serializable data class PlaylistRoute(val id: String)
@Serializable object NowPlayingRoute
@Serializable data class SearchRoute(val query: String = "")
@Serializable object SettingsRoute
@Serializable object DownloadsRoute
@Serializable object DiscoverRoute
@Serializable object DevicesRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark, so system bar icons must always be light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as MusikboxApp).container
        if (container.settings.current.credentials != null) container.player.connect()
        setContent { MusikboxRoot(container) }
    }

    override fun onStart() {
        super.onStart()
        (application as MusikboxApp).container.onForeground()
    }
}

@Composable
private fun MusikboxRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    MusikboxTheme(pureBlack = settings.pureBlack) {
        CompositionLocalProvider(LocalAppContainer provides container, LocalSnackbar provides snackbar) {
            val nav = rememberNavController()
            val start: Any = remember { if (settings.credentials == null) LoginRoute else HomeRoute }
            val player by container.player.state.collectAsStateWithLifecycle()
            val entry by nav.currentBackStackEntryAsState()
            val dest = entry?.destination
            val signedIn = settings.credentials != null
            val showMini = signedIn && player.hasMedia && dest != null &&
                !dest.hasRoute<NowPlayingRoute>() && !dest.hasRoute<LoginRoute>()

            if (signedIn) NotificationPermission()

            Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBottomInset provides if (showMini) MiniPlayerInset else 0.dp) {
                    NavHost(
                        navController = nav,
                        startDestination = start,
                        enterTransition = { slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280)) },
                    ) {
                        composable<LoginRoute> {
                            LoginScreen(onSignedIn = {
                                container.player.connect()
                                nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                            })
                        }
                        composable<HomeRoute> {
                            HomeScreen(
                                onOpenPlaylist = { nav.navigate(PlaylistRoute(it)) },
                                onOpenSearch = { nav.navigate(SearchRoute(it)) },
                                onOpenSettings = { nav.navigate(SettingsRoute) },
                                onOpenDownloads = { nav.navigate(DownloadsRoute) },
                                onOpenDiscover = { nav.navigate(DiscoverRoute) },
                            )
                        }
                        composable<PlaylistRoute> { backStack ->
                            val route = backStack.toRoute<PlaylistRoute>()
                            PlaylistScreen(route.id, onBack = { nav.popBackStack() })
                        }
                        composable<SearchRoute> { backStack ->
                            SearchScreen(
                                initialQuery = backStack.toRoute<SearchRoute>().query,
                                onBack = { nav.popBackStack() },
                                onOpenPlaylist = { nav.navigate(PlaylistRoute(it)) },
                            )
                        }
                        composable<DiscoverRoute> { DiscoverScreen(onBack = { nav.popBackStack() }) }
                        composable<DevicesRoute> { DevicesScreen(onBack = { nav.popBackStack() }) }
                        composable<DownloadsRoute> {
                            DownloadsScreen(onBack = { nav.popBackStack() }, onOpenPlaylist = { nav.navigate(PlaylistRoute(it)) })
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onSignedOut = { nav.navigate(LoginRoute) { popUpTo(0) { inclusive = true } } },
                                onOpenDownloads = { nav.navigate(DownloadsRoute) },
                                onOpenDevices = { nav.navigate(DevicesRoute) },
                            )
                        }
                        composable<NowPlayingRoute>(
                            enterTransition = { slideInVertically(tween(380)) { it } },
                            exitTransition = { fadeOut(tween(200)) },
                            popEnterTransition = { fadeIn(tween(200)) },
                            popExitTransition = { slideOutVertically(tween(320)) { it } },
                        ) {
                            NowPlayingScreen(
                                onCollapse = { nav.popBackStack() },
                                onOpenPlaylist = { id ->
                                    nav.popBackStack()
                                    nav.navigate(PlaylistRoute(id)) { launchSingleTop = true }
                                },
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showMini,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    MiniPlayer(onOpen = { nav.navigate(NowPlayingRoute) { launchSingleTop = true } })
                }

                SnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (showMini) MiniPlayerInset else 8.dp),
                ) { data ->
                    Snackbar(
                        data,
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            }
        }
    }
}

/** Media and download notifications need POST_NOTIFICATIONS on Android 13+. Ask once. */
@Composable
private fun NotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
