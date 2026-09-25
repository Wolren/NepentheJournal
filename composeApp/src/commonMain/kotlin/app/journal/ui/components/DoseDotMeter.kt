package app.journal.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dose magnitude as a small matrix of dots: full dots for whole reference
 * amounts, a hollow dot for the remainder. Pure math in [doseDots] so the
 * counting rules are testable without a renderer.
 *
 * @param filled Number of completely filled dots.
 * @param hasPartial Whether a hollow dot follows the filled ones.
 * @param total Dots to draw, `filled + (hasPartial ? 1 : 0)`, always 0..[DOSE_DOT_MAX].
 */
data class DoseDots(val filled: Int, val hasPartial: Boolean, val total: Int)

/** Dots per reference dose: four means each dot is a quarter of the typical dose. */
const val DOSE_DOT_REFERENCE_MULTIPLIER = 4

/** Hard ceiling of the matrix (4 columns x 5 rows). */
const val DOSE_DOT_MAX = 20

/**
 * How many dots a dose covers, in units of [reference]. A dose smaller than a
 * dot still shows a hollow dot (it exists, just under one quantum); anything
 * that would not fill at least a fifteenth of a dot shows nothing at all, so
 * a hint of a substance never renders as a full grid. Returns null when there
 * is no usable reference - callers then omit the meter rather than guess.
 */
fun doseDots(amount: Double, reference: Double?): DoseDots? {
    if (reference == null || reference <= 0.0) return null
    if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return null
    val exact = (amount / reference) * DOSE_DOT_REFERENCE_MULTIPLIER
    val capped = exact.coerceAtMost(DOSE_DOT_MAX.toDouble())
    val filled = capped.toInt()
    val hasPartial = capped - filled >= 0.15
    val total = filled + if (hasPartial) 1 else 0
    if (total == 0) return null
    return DoseDots(filled = filled, hasPartial = hasPartial, total = total)
}

/**
 * The dot matrix itself. Renders nothing when [amount] has no reference or
 * covers no dots, which keeps rows without a comparable substance clean.
 */
@Composable
fun DoseDotMeter(
    amount: Double,
    reference: Double?,
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    gap: Dp = 3.dp,
    columns: Int = 4,
) {
    val dots = remember(amount, reference) { doseDots(amount, reference) } ?: return
    val hollowStroke = remember(color) {
        BorderStroke(1.2.dp, color.copy(alpha = 0.65f))
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        dots.total
            .coerceAtLeast(1)
            .chunked(columns.coerceAtLeast(1))
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { index ->
                        val filled = index < dots.filled
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .let {
                                    if (filled) it.background(color, CircleShape)
                                    else it.border(hollowStroke, CircleShape)
                                },
                        )
                    }
                }
            }
    }
}
