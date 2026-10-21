package app.musikbox.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.musikbox.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Extra bottom space screens must leave for the floating mini player. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }
