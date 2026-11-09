package app.musikbox.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.audio.EQ_BANDS_HZ
import app.musikbox.audio.EQ_CUSTOM
import app.musikbox.audio.EQ_MAX_DB
import app.musikbox.audio.EQ_PRESETS
import app.musikbox.audio.eqBandLabel
import app.musikbox.ui.LocalAppContainer
import java.util.Locale

@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        EqualizerPanel(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
    }
}

@Composable
fun EqualizerPanel(modifier: Modifier = Modifier) {
    val controller = LocalAppContainer.current.equalizer
    val eq by controller.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (eq.enabled) "Changes apply as you move them" else "Off: music plays as it was recorded",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = eq.enabled, onCheckedChange = { on -> controller.update { it.copy(enabled = on) } })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EQ_PRESETS.forEach { preset ->
                FilterChip(
                    selected = eq.enabled && eq.preset == preset.name,
                    onClick = { controller.update { it.copy(enabled = true).withPreset(preset) } },
                    label = { Text(preset.name) },
                )
            }
            if (eq.preset == EQ_CUSTOM) FilterChip(selected = eq.enabled, onClick = {}, label = { Text(EQ_CUSTOM) })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(240.dp)) {
            EQ_BANDS_HZ.forEachIndexed { i, hz ->
                val gain = eq.gains[i]
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(gainLabel(gain), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VerticalSlider(
                        value = gain,
                        // Moving a band means wanting the equalizer: it turns on by itself.
                        onValueChange = { v -> controller.update { it.copy(enabled = true).withBand(i, v) } },
                        dimmed = !eq.enabled,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "${eqBandLabel(hz)} hertz" },
                    )
                    Text(eqBandLabel(hz), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { controller.update { it.withPreset(EQ_PRESETS.first()) } }) { Text("Reset to flat") }
        }
    }
}

/** A Material slider stood on end: up is a boost, down a cut. */
@Composable
private fun VerticalSlider(value: Float, onValueChange: (Float) -> Unit, dimmed: Boolean, modifier: Modifier = Modifier) {
    val colors = if (dimmed) {
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.outline,
            activeTrackColor = MaterialTheme.colorScheme.outline,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    } else SliderDefaults.colors()
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = -EQ_MAX_DB..EQ_MAX_DB,
        colors = colors,
        modifier = modifier
            .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
            }
            .width(240.dp),
    )
}

private fun gainLabel(db: Float): String = when {
    db > 0.04f -> "+" + String.format(Locale.ROOT, "%.1f", db).removeSuffix(".0")
    db < -0.04f -> "−" + String.format(Locale.ROOT, "%.1f", -db).removeSuffix(".0")
    else -> "0"
}
