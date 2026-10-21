package app.musikbox.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.musikbox.desktop.ui.AppUiState
import app.musikbox.desktop.ui.MusikboxApp
import app.musikbox.desktop.ui.Navigator
import app.musikbox.desktop.ui.Screen
import app.musikbox.desktop.ui.TextFocus
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Image
import java.awt.Dimension

fun main() {
    // Sharper text on HiDPI X11/XWayland and a proper WM_CLASS for the taskbar/.desktop match.
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "musikbox")
    }

    val container = DesktopContainer()
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("musikbox.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application(exitProcessOnExit = true) {
        val nav = remember { Navigator() }
        val ui = remember { AppUiState() }
        val snackbar = remember { SnackbarHostState() }
        val initial = container.settings.current
        val windowState = rememberWindowState(size = DpSize(initial.windowWidth.dp, initial.windowHeight.dp))

        setSingletonImageLoaderFactory { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
                .memoryCache { MemoryCache.Builder().maxSizeBytes(160L * 1024 * 1024).build() }
                .diskCache { DiskCache.Builder().directory(AppPaths.artworkDir.toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
                .crossfade(true)
                .build()
        }

        fun quit() {
            container.settings.update {
                it.copy(windowWidth = windowState.size.width.value.toInt(), windowHeight = windowState.size.height.value.toInt())
            }
            container.shutdown()
            exitApplication()
        }

        Window(
            onCloseRequest = ::quit,
            state = windowState,
            title = "musikbox",
            icon = icon,
            // Preview: runs before a focused button can treat Space as a click.
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                if (e.isCtrlPressed && e.key == Key.Q) { quit(); return@Window true }
                if (container.settings.current.credentials == null) return@Window false
                val p = container.player
                val typing = TextFocus.active
                when {
                    !typing && e.key == Key.Spacebar && !e.isCtrlPressed -> { p.togglePlay(); true }
                    !typing && e.isCtrlPressed && e.key == Key.DirectionRight -> { p.next(); true }
                    !typing && e.isCtrlPressed && e.key == Key.DirectionLeft -> { p.previous(); true }
                    !typing && e.isCtrlPressed && e.key == Key.DirectionUp -> { p.setVolume(p.state.value.volume + 5); true }
                    !typing && e.isCtrlPressed && e.key == Key.DirectionDown -> { p.setVolume(p.state.value.volume - 5); true }
                    e.isCtrlPressed && e.key == Key.F -> { nav.go(Screen.Search()); true }
                    e.isAltPressed && e.key == Key.DirectionLeft -> { nav.back(); true }
                    e.key == Key.Escape && nav.current == Screen.NowPlaying -> { nav.back(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = Dimension(980, 620)
            // Coming back to the window: pull changes other devices made meanwhile.
            DisposableEffect(window) {
                val focus = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = container.onForeground()
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus) }
            }
            LaunchedEffect(Unit) {
                container.start(
                    onRaise = { window.isVisible = true; window.toFront(); window.requestFocus() },
                    onQuit = ::quit,
                )
            }
            MusikboxApp(container, nav, ui, snackbar)
        }
    }
}
