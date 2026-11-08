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
            val firstDose = doses.minOfOrNull { it.timestamp } ?: session.startTime
            doses.groupBy { it.substanceId }
                .mapNotNull { (substanceId, group) ->
                    val substance = substancesById[substanceId] ?: return@mapNotNull null
                    // No window: the curve runs to its own tail, so the chart sees the whole effect.
                    val pk = pkEffectSamples(
                        doses = group,
                        substance = substance,
                        startMs = minOf(firstDose, group.minOf { it.timestamp }),
                    )
                    if (pk != null && pk.samples.size >= 2) {
                        return@mapNotNull EffectCurveLayer(substanceId, pk.samples)
                    }
                    val profile = parseDurationProfile(substance.durationProfile)
                    if (profile.isEmpty()) return@mapNotNull null
                    val samples = synthesizedEffectSamples(profile, group.minOf { it.timestamp })
                    if (samples.isEmpty()) null else EffectCurveLayer(substanceId, samples)
                }
                .sortedBy { it.samples.first().timestampMs }
        }
    }
    val halfLifeNote = remember(doses, substancesById) {
        if (loggedEffectSamples(events, session.checkins).isNotEmpty()) return@remember null
        doses.groupBy { it.substanceId }.entries.firstNotNullOfOrNull { (id, group) ->
            val substance = substancesById[id] ?: return@firstNotNullOfOrNull null
            val window = group.minOf { it.timestamp }..group.maxOf { it.timestamp }.coerceAtLeast(group.minOf { it.timestamp } + 1)
            pkEffectSamples(group, substance, window.first, window.last)?.let { pk ->
                val source = if (pk.halfLifeFromSource) "from data" else "estimated from duration"
                "${substance.name}: half-life ${formatHalfLife(pk.halfLifeMinutes)} ($source)"
            }
        }
    }

    // The window follows the curves and the doses. Anchoring it at session.startTime drew dead
    // space from "session started" to the first dose and cut curves that ran past the session.
    val startMs = minOf(
        doses.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE,
        layers.minOfOrNull { it.samples.first().timestampMs } ?: Long.MAX_VALUE,
    ).takeIf { it != Long.MAX_VALUE } ?: session.startTime
    val endMs = maxOf(
        doses.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE,
        layers.maxOfOrNull { it.samples.last().timestampMs } ?: Long.MIN_VALUE,
        // A live session still ends at now, so the axis does not stop at the last dose.
        if (session.endTime == null) now else Long.MIN_VALUE,
    ).takeIf { it != Long.MIN_VALUE } ?: (startMs + 60_000L)
    val dataSpanMs = (endMs - startMs).coerceAtLeast(60_000L)
    // Breathing room on the right so the curve ends inside the frame instead of at its edge.
    val spanMs = dataSpanMs + dataSpanMs / 40

    val tickStep = remember(spanMs) { axisStepMs(spanMs) }
    val labels = remember(spanMs, tickStep) {
        val out = mutableListOf<TickLabel>()
        // Starts at 0 so the scale reads from the origin rather than from the first step.
        var offset = 0L
        while (offset < spanMs) {
            // axisLabel(0) is "0m", which would contradict the "1h 2h ..." it sits among on an hour-stepped axis.
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
            val container = MaterialTheme.colorScheme.surfaceVariant
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
                    // Catmull-Rom through the samples: the tangent at each point comes from its
                    // neighbours, so passing through a peak keeps the slope instead of kinking it.
                    val xs = samples.map { xAt(it.timestampMs) }
                    val ys = samples.map { yAt(it.intensity) }
                    linePath.moveTo(xs[0], ys[0])
                    if (samples.size == 1) {
                        linePath.lineTo(xs[0] + 1f, ys[0])
                    } else {
                        for (i in 0 until samples.size - 1) {
                            val x0 = xs.getOrElse(i - 1) { xs[i] }
                            val y0 = ys.getOrElse(i - 1) { ys[i] }
                            val x1 = xs[i]
                            val y1 = ys[i]
                            val x2 = xs[i + 1]
                            val y2 = ys[i + 1]
                            val x3 = xs.getOrElse(i + 2) { xs[i + 1] }
                            val y3 = ys.getOrElse(i + 2) { ys[i + 1] }
                            // Uneven sample spacing lets the raw tangents cross, which draws a loop;
                            // clamping each control point into its own segment keeps x monotone.
                            val lo = minOf(x1, x2)
                            val hi = maxOf(x1, x2)
                            val c1x = (x1 + (x2 - x0) / 6f).coerceIn(lo, hi)
                            val c1y = (y1 + (y2 - y0) / 6f).coerceIn(0f, h)
                            val c2x = (x2 - (x3 - x1) / 6f).coerceIn(lo, hi)
                            val c2y = (y2 - (y3 - y1) / 6f).coerceIn(0f, h)
                            linePath.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
                        }
                    }
                    val areaPath = Path()
                    areaPath.addPath(linePath)
                    areaPath.lineTo(xs.last(), h)
                    areaPath.lineTo(xs.first(), h)
                    areaPath.close()
                    return linePath to areaPath
                }

                val built = layers.map { buildPaths(it.samples) }
                // The baseline goes down first: drawn last it hid the near-zero start of every curve.
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.dp.toPx(),
                )
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
            halfLifeNote?.let { note ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private data class EffectCurveLayer(val substanceId: String?, val samples: List<EffectSample>)
