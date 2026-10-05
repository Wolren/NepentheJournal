package app.journal.ui.charts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** One axis label at a fractional position across the plot width. */
internal data class TickLabel(val fraction: Float, val text: String, val emphasis: Boolean = false)

/**
 * Row of axis labels placed at their true fractional positions, so they line
 * up with the data regardless of how wide the text measures. The last label
 * can be marked [TickLabel.emphasis] to stand out the way the reference's
 * end-of-axis marker does.
 */
@Composable
internal fun TickLabelRow(labels: List<TickLabel>, modifier: Modifier = Modifier) {
    Layout(
        content = {
            labels.forEach {
                Text(
                    text = it.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.emphasis) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    },
                    fontWeight = if (it.emphasis) FontWeight.SemiBold else null,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        if (width <= 0 || width == Constraints.Infinity || measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        layout(width, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { index, placeable ->
                val center = labels[index].fraction * width
                val x = (center - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}
