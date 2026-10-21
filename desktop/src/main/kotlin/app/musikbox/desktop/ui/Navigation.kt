package app.musikbox.desktop.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import app.musikbox.desktop.DesktopContainer

sealed interface Screen {
    data object Home : Screen
    data class Playlist(val id: String) : Screen
    data class Search(val query: String = "") : Screen
    data object Downloads : Screen
    data object Discover : Screen
    data object Devices : Screen
    data object Settings : Screen
    data object NowPlaying : Screen
}

class Navigator {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(screen: Screen) {
        if (current == screen) return
        // Top-level destinations don't pile up: going Home clears the stack.
        if (screen == Screen.Home) { reset(); return }
        stack.add(screen)
    }

    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun reset() { stack.clear(); stack.add(Screen.Home) }
}

val LocalContainer = staticCompositionLocalOf<DesktopContainer> { error("No container") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("No navigator") }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Whether a text field has keyboard focus. Global shortcuts (Space, Ctrl+arrows) are
 * handled before focused widgets see them, so they must stand aside while the user types.
 */
object TextFocus {
    @Volatile var active = false
}

fun Modifier.tracksTextFocus(): Modifier = onFocusChanged { TextFocus.active = it.isFocused }
