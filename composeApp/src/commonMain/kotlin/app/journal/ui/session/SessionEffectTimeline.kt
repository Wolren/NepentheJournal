package app.journal.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.ui.session.timeline.axisLabel
import app.journal.ui.session.timeline.axisStepMs
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.currentTimeMillis
import app.journal.util.formatDuration
import app.journal.util.parseDurationProfile
import kotlin.math.roundToInt

/**
 * "Effect timeline" section of a session card: an area curve of intensity over
 * the session window with a marker on every dose, plus an hour axis.
 *
 * The curve is what the user logged (timeline events and check-ins) when they
 * exist. When they don't, it falls back to a curve derived from the substance's
 * own duration profile, which is the same shape a dose is expected to make.
 * With neither, the card says so instead of drawing an invented line.
 *
 * The reference design's "Info" and expand affordances are deliberately not
 * reproduced: they open things this card has nowhere to send, so the header
 * carries the window length instead of two dead controls.
 */
@Composable
internal fun SessionEffectTimeline(
    session: Session,
    doses: List<Dose>,
    events: List<TimelineEvent>,
    substancesById: Map<String, Substance>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val isDark = isDarkTheme()
    val now = currentTimeMillis()

    val curve = remember(events, session.checkins, doses, substancesById) {
        val logged = loggedEffectSamples(events, session.checkins)
        if (logged.isNotEmpty()) {
            logged
        } else {
            val anchorDose = doses.minByOrNull { it.timestamp }
            val profile = anchorDose
                ?.let { substancesById[it.substanceId]?.durationProfile }
                ?.let { parseDurationProfile(it) }
                ?: emptyList()
            if (profile.isEmpty() || anchorDose == null) emptyList()
            else synthesizedEffectSamples(profile, anchorDose.timestamp)
        }
    }

    // Window: at minimum the session itself, stretched by anything that
    // happened before the start (an early dose) or after the end.
    val startMs = minOf(
        session.startTime,
        doses.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE,
        curve.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
    )
    val endMs = maxOf(
        session.endTime ?: now,
        doses.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE,
        curve.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
    )
    val spanMs = (endMs - startMs).coerceAtLeast(60_000L)

    val tickStep = remember(spanMs) { axisStepMs(spanMs) }
    val labels = remember(spanMs, tickStep) {
        val out = mutableListOf<TickLabel>()
        var offset = tickStep
        while (offset < spanMs) {
            out.add(TickLabel(fraction = offset.toFloat() / spanMs, text = axisLabel(offset)))
            offset += tickStep
        }
        out
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Effect timeline",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(startMs, startMs + spanMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(8.dp))

        if (curve.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(88.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No intensity logged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        } else {
            val surface = MaterialTheme.colorScheme.surfaceVariant
            Canvas(
                modifier = Modifier.fillMaxWidth().height(100.dp),
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                fun xAt(timestampMs: Long): Float =
                    ((timestampMs - startMs).toFloat() / spanMs) * w
                fun yAt(level: Float): Float {
                    val topPad = 8.dp.toPx()
                    return h - (level.coerceIn(0f, 10f) / 10f) * (h - topPad)
                }

                val linePath = Path()
                val first = curve.first()
                linePath.moveTo(xAt(first.timestampMs), yAt(first.intensity))
                for (i in 1 until curve.size) {
                    val prev = curve[i - 1]
                    val cur = curve[i]
                    val x0 = xAt(prev.timestampMs)
                    val y0 = yAt(prev.intensity)
                    val x1 = xAt(cur.timestampMs)
                    val y1 = yAt(cur.intensity)
                    // Smooth join: control points sit on the horizontal midpoint,
                    // which rounds the corners the way the reference curve does.
                    val midX = (x0 + x1) / 2f
                    linePath.cubicTo(midX, y0, midX, y1, x1, y1)
                }

                val areaPath = Path()
                areaPath.addPath(linePath)
                areaPath.lineTo(xAt(curve.last().timestampMs), h)
                areaPath.lineTo(xAt(first.timestampMs), h)
                areaPath.close()

                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.38f),
                            accent.copy(alpha = 0.05f),
                        ),
                    ),
                )
                drawPath(
                    path = linePath,
                    color = accent,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.dp.toPx(),
                )

                // One marker per dose, sitting on the curve at the dose's time.
                val markerRadius = 4.5.dp.toPx()
                val halo = 1.5.dp.toPx()
                doses.forEach { dose ->
                    val x = xAt(dose.timestamp)
                    if (x < -markerRadius || x > w + markerRadius) return@forEach
                    val name = substancesById[dose.substanceId]?.name ?: dose.substanceId
                    val color = AdaptiveColors.colorFor(name).getComposeColor(isDark)
                    val y = yAt(effectIntensityAt(curve, dose.timestamp))
                    drawCircle(color = surface, radius = markerRadius + halo)
                    drawCircle(color = color, radius = markerRadius)
                }
            }

            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                TickLabelRow(labels)
            }
        }
    }
}

private data class TickLabel(val fraction: Float, val text: String)

/**
 * Row of axis labels placed at their true fractional positions, so they line
 * up with the curve regardless of how wide the text measures.
 */
@Composable
private fun TickLabelRow(labels: List<TickLabel>, modifier: Modifier = Modifier) {
    Layout(
        content = {
            labels.forEach {
                Text(
                    text = it.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
        modifier = modifier.fillMaxWidth().height(16.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        if (width <= 0 || measurables.isEmpty()) return@Layout layout(0, 0) {}
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
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
