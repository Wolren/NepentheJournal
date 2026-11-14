package app.journal.ui.charts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class TickLabel(val fraction: Float, val text: String, val emphasis: Boolean = false)

/** Clear space kept between two placed axis labels. */
private val TickLabelGap = 6.dp

/**
 * Left x for each label across [width], or null where a label is dropped
 * because it would sit closer than [gap] to its neighbour. The last label
 * always survives, so the end-of-axis marker stays; the first survives when
 * it clears that marker.
 */
internal fun placeTickLabels(
    fractions: List<Float>,
    widths: List<Int>,
    width: Int,
    gap: Int,
): List<Int?> {
    val placed = MutableList<Int?>(fractions.size) { null }
    if (width <= 0 || fractions.isEmpty()) return placed

    val endIndex = fractions.lastIndex
    val xs = fractions.mapIndexed { index, fraction ->
        (fraction * width - widths[index] / 2f).roundToInt()
            .coerceIn(0, (width - widths[index]).coerceAtLeast(0))
    }
    placed[endIndex] = xs[endIndex]
    if (endIndex == 0) return placed

    val firstFits = xs[0] + widths[0] + gap <= xs[endIndex]
    placed[0] = if (firstFits) xs[0] else null
    var cursor = if (firstFits) xs[0] + widths[0] else 0
    for (index in 1 until endIndex) {
        val right = xs[index] + widths[index]
        if (xs[index] >= cursor + gap && right + gap <= xs[endIndex]) {
            placed[index] = xs[index]
            cursor = right
        }
    }
    return placed
}

/**
 * Row of axis labels placed at their true fractional positions, so they line
 * up with the data regardless of how wide the text measures. Labels that
 * would collide at the current width are dropped instead of overlapping. The
 * last label can be marked [TickLabel.emphasis] to stand out the way the
 * reference's end-of-axis marker does.
 */
@Composable
internal fun TickLabelRow(labels: List<TickLabel>, modifier: Modifier = Modifier) {
    val gap = with(LocalDensity.current) { TickLabelGap.roundToPx() }
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
        val xs = placeTickLabels(
            fractions = labels.map { it.fraction },
            widths = placeables.map { it.width },
            width = width,
            gap = gap,
        )
        layout(width, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { index, placeable ->
                xs[index]?.let { placeable.place(it, 0) }
            }
        }
    }
}