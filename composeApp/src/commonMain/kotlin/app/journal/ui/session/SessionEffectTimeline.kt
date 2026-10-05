package app.journal.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.ui.charts.TickLabel
import app.journal.ui.charts.TickLabelRow
import app.journal.ui.session.timeline.axisLabel
import app.journal.ui.session.timeline.axisStepMs
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.currentTimeMillis
import app.journal.util.formatDuration
import app.journal.util.parseDurationProfile

/**
 * "Effect timeline" section of a session card: area curves of intensity over
 * the session window with a marker on every dose, plus an hour axis below the
 * plot that runs from 0h.
 *
 * The curve is what the user logged (timeline events and check-ins) when they
 * exist. When they don't, each substance in the session gets its own curve
 * derived from that substance's duration profile, anchored at that substance's
 * first dose - so a redose or a second substance shows as its own hill rather
 * than being flattened into one line. The first curve is solid, later ones
 * dashed, which is how the reference tells overlapping curves apart.
 * With nothing derivable, the card says so instead of drawing an invented line.
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

    val layers = remember(events, session.checkins, doses, substancesById) {
        val logged = loggedEffectSamples(events, session.checkins)
        if (logged.isNotEmpty()) {
            listOf(EffectCurveLayer(substanceId = null, samples = logged))
        } else {
            doses.groupBy { it.substanceId }
                .mapNotNull { (substanceId, group) ->
                    val profile = substancesById[substanceId]?.durationProfile
                        ?.let { parseDurationProfile(it) }
                    if (profile.isNullOrEmpty()) return@mapNotNull null
                    val samples = synthesizedEffectSamples(profile, group.minOf { it.timestamp })
                    if (samples.isEmpty()) null else EffectCurveLayer(substanceId, samples)
                }
                .sortedBy { it.samples.first().timestampMs }
        }
    }

    // Window: at minimum the session itself, stretched by anything that
    // happened before the start (an early dose) or after the end.
    val startMs = minOf(
        session.startTime,
        doses.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE,
        layers.minOfOrNull { it.samples.first().timestampMs } ?: Long.MAX_VALUE,
    )
    val endMs = maxOf(
        session.endTime ?: now,
        doses.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE,
        layers.maxOfOrNull { it.samples.last().timestampMs } ?: Long.MIN_VALUE,
    )
    val spanMs = (endMs - startMs).coerceAtLeast(60_000L)

    val tickStep = remember(spanMs) { axisStepMs(spanMs) }
    val labels = remember(spanMs, tickStep) {
        val out = mutableListOf<TickLabel>()
        // Starts at 0 so the scale reads from the origin rather than from the
        // first step. TickLabelRow clamps into the row, so a 0-fraction label
        // sits flush against the left edge instead of half-hanging off it.
        var offset = 0L
        while (offset < spanMs) {
            // axisLabel(0) is "0m", which would contradict the "1h 2h ..." it
            // sits among on an hour-stepped axis. Match the neighbour's unit:
            // a step of a full hour or more means the axis reads in hours.
            val text = if (offset == 0L && tickStep >= 3_600_000L) "0h" else axisLabel(offset)
            out.add(TickLabel(fraction = offset.toFloat() / spanMs, text = text))
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
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(startMs, startMs + spanMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(8.dp))

        if (layers.isEmpty()) {
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
            // The chart lives inside a surfaceVariant card, so both the fill
            // behind it and the marker halos must be that same container color.
            val container = MaterialTheme.colorScheme.surfaceVariant
            // Resolved out here: getComposeColor is @Composable and the canvas
            // draw pass is not a composable context. A curve and its dose dots
            // share one color per substance, so dots read as the legend.
            val colorForSubstance = doses.distinctBy { it.substanceId }.associate { dose ->
                val name = substancesById[dose.substanceId]?.name ?: dose.substanceId
                dose.substanceId to AdaptiveColors.colorFor(name).getComposeColor(isDark)
            }
            val markerColorOf = doses.associate { it.id to (colorForSubstance[it.substanceId] ?: accent) }
            val layerColors = layers.map { layer ->
                layer.substanceId?.let { colorForSubstance[it] } ?: accent
            }

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

                fun buildPaths(samples: List<EffectSample>): Pair<Path, Path> {
                    val linePath = Path()
                    val first = samples.first()
                    linePath.moveTo(xAt(first.timestampMs), yAt(first.intensity))
                    for (i in 1 until samples.size) {
                        val prev = samples[i - 1]
                        val cur = samples[i]
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
                    areaPath.lineTo(xAt(samples.last().timestampMs), h)
                    areaPath.lineTo(xAt(first.timestampMs), h)
                    areaPath.close()
                    return linePath to areaPath
                }

                // Every fill first, then every stroke, so overlapping hills
                // never have a line buried under a neighbour's wash.
                val built = layers.map { buildPaths(it.samples) }
                layers.forEachIndexed { index, _ ->
                    val (_, areaPath) = built[index]
                    val color = layerColors[index]
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                color.copy(alpha = 0.45f),
                                color.copy(alpha = 0.06f),
                            ),
                        ),
                    )
                }
                layers.forEachIndexed { index, _ ->
                    val (linePath, _) = built[index]
                    drawPath(
                        path = linePath,
                        color = layerColors[index],
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            // Later curves dash so crossings stay readable.
                            pathEffect = if (index == 0) null else PathEffect.dashPathEffect(
                                floatArrayOf(9.dp.toPx(), 7.dp.toPx()),
                            ),
                        ),
                    )
                }
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.dp.toPx(),
                )

                // Dose markers rest on the axis at their own time, tinted with
                // their substance's color - the reference puts them on the
                // baseline rather than on the curve, and with several curves
                // overlapping that is also the only unambiguous place.
                val markerRadius = 4.5.dp.toPx()
                val halo = 1.5.dp.toPx()
                doses.forEach { dose ->
                    val x = xAt(dose.timestamp)
                    if (x < -markerRadius || x > w + markerRadius) return@forEach
                    val color = markerColorOf[dose.id] ?: accent
                    val position = Offset(x, h - markerRadius)
                    drawCircle(color = container, radius = markerRadius + halo, center = position)
                    drawCircle(color = color, radius = markerRadius, center = position)
                }
            }

            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                TickLabelRow(labels)
            }
        }
    }
}

/** One drawn curve: which substance it belongs to (null = the logged curve) and its samples. */
private data class EffectCurveLayer(val substanceId: String?, val samples: List<EffectSample>)
