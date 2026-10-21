package app.musikbox.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement

/** Thin, custom seek bar: tap or drag anywhere; thickens while scrubbing. */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMs.coerceAtLeast(1L))
    val seek by rememberUpdatedState(onSeek)
    val fraction = dragFraction ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    val thickness by animateDpAsState(if (dragFraction != null) 7.dp else 4.dp, label = "seekThickness")
    val thumb by animateDpAsState(if (dragFraction != null) 9.dp else 6.dp, label = "seekThumb")

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> seek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { seek((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2
            val stroke = thickness.toPx()
            drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width * buffered, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
            drawCircle(Color.White, thumb.toPx(), Offset(size.width * fraction, y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragFraction?.let { (it * duration).toLong() } ?: positionMs
            Text(formatTrackTime(shown), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Text(
                "-" + formatTrackTime((durationMs - shown).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}
