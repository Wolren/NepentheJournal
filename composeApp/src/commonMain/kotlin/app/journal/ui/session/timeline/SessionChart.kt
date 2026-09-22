package app.journal.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.IJournalRepository
import app.journal.model.CheckIn
import app.journal.model.Dose
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.charts.ChartCard
import app.journal.ui.components.PhaseColors
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.isDarkTheme
import app.journal.ui.theme.foregroundFor
import app.journal.util.currentTimeMillis
import io.github.koalaplot.core.legend.FlowLegend
import io.github.koalaplot.core.line.AreaBaseline
import io.github.koalaplot.core.line.AreaPlot
import io.github.koalaplot.core.line.LinePlot
import io.github.koalaplot.core.style.AreaStyle
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.xygraph.DefaultPoint
import io.github.koalaplot.core.xygraph.Point
import io.github.koalaplot.core.xygraph.VerticalLineAnnotation
import io.github.koalaplot.core.xygraph.XYGraph
import io.github.koalaplot.core.xygraph.rememberAxisContent
import io.github.koalaplot.core.xygraph.rememberFloatLinearAxisModel

private data class RibbonSegment(
    val label: String,
    val color: Color,
    val startFrac: Float,
    val endFrac: Float,
)

private fun phaseLabelFor(type: TimelineEventType): String = when (type) {
    TimelineEventType.ONSET -> "Onset"
    TimelineEventType.COMEUP -> "Comeup"
    TimelineEventType.PEAK -> "Peak"
    TimelineEventType.PLATEAU -> "Plateau"
    TimelineEventType.OFFSET -> "Offset"
    TimelineEventType.AFTERGLOW -> "Afterglow"
    else -> "Event"
}

/** Linear interpolation of sparse samples, densified for a smooth area fill. */
private fun densify(samples: List<Point<Float, Float>>, step: Float): List<Point<Float, Float>> {
    if (samples.size < 2) return samples
    val out = mutableListOf<Point<Float, Float>>()
    for (i in 0 until samples.size - 1) {
        val a = samples[i]
        val b = samples[i + 1]
        out.add(a)
        val span = b.x - a.x
        if (span > step * 1.5f) {
            var x = a.x + step
            while (x < b.x) {
                val t = (x - a.x) / span
                out.add(DefaultPoint(x, a.y + (b.y - a.y) * t))
                x += step
            }
        }
    }
    out.add(samples.last())
    return out
}

private fun intensityAt(samples: List<Point<Float, Float>>, x: Float, fallback: Float): Float {
    if (samples.isEmpty()) return fallback
    if (x <= samples.first().x) return samples.first().y
    if (x >= samples.last().x) return samples.last().y
    for (i in 0 until samples.size - 1) {
        val a = samples[i]
        val b = samples[i + 1]
        if (x in a.x..b.x) {
            val t = if (b.x == a.x) 0f else (x - a.x) / (b.x - a.x)
            return a.y + (b.y - a.y) * t
        }
    }
    return fallback
}

/**
 * Session timeline header chart: phase ribbon, KoalaPlot intensity area with
 * dose markers, tap-to-inspect cursor, and a single legend row.
 */
@Composable
internal fun TimelineBar(
    startTime: Long,
    endTime: Long?,
    events: List<TimelineEvent>,
    checkins: List<CheckIn>,
    doses: List<Dose>,
    repo: IJournalRepository,
    shulginRating: String? = null,
) {
    val isDark = isDarkTheme()
    val now = currentTimeMillis()
    val totalDuration = (endTime ?: now) - startTime
    val rangeMs = totalDuration.coerceAtLeast(1L)

    // Batch-resolve substance names once per dose list; dose marks reuse the
    // same map instead of hitting the repository a second time.
    val substanceNameMap = remember(doses) {
        doses.associate { d -> d.substanceId to (repo.getSubstance(d.substanceId)?.name ?: d.substanceId) }
    }
    val substanceNames = remember(substanceNameMap) {
        substanceNameMap.values.distinct()
    }
    // Plain vals: getComposeColor is @Composable, so this stays out of remember.
    val fallbackPrimary = MaterialTheme.colorScheme.primary
    val subColors = substanceNames.associateWith {
        AdaptiveColors.colorFor(it).getComposeColor(isDark)
    }
    val phaseEvents = remember(events) {
        events.filter { it.eventType in phaseColors }.sortedBy { it.timestamp }
    }

    // Data-fitted window so the chart maps where the data is, not empty margins.
    val dataTimes = remember(doses, events, checkins) {
        buildList {
            doses.forEach { add(it.timestamp) }
            events.forEach { add(it.timestamp) }
            checkins.forEach { add(it.timestamp) }
        }
    }
    val windowPad = remember(rangeMs) { maxOf((rangeMs * 0.05).toLong(), 60_000L) }
    val windowStart = if (dataTimes.isEmpty()) startTime else dataTimes.min() - windowPad
    val windowEnd = when {
        dataTimes.isEmpty() -> startTime + rangeMs
        endTime == null -> maxOf(dataTimes.max(), now)
        else -> dataTimes.max() + windowPad
    }
    val windowSpan = (windowEnd - windowStart).coerceAtLeast(1L)
    val spanMin = windowSpan / 60_000f
    fun toMin(ts: Long): Float = ((ts - windowStart).coerceAtLeast(0L)) / 60_000f

    val ribbonSegments = remember(phaseEvents, windowStart, windowSpan) {
        val fallback = listOf(
            Triple("Onset", TimelineEventType.ONSET, 0f to 0.25f),
            Triple("Comeup", TimelineEventType.COMEUP, 0.25f to 0.50f),
            Triple("Peak", TimelineEventType.PEAK, 0.50f to 0.75f),
            Triple("Offset", TimelineEventType.OFFSET, 0.75f to 1.0f),
        )
        if (phaseEvents.size >= 2) {
            val segs = mutableListOf<RibbonSegment>()
            for (i in phaseEvents.indices) {
                val ev = phaseEvents[i]
                val nextT = if (i + 1 < phaseEvents.size) phaseEvents[i + 1].timestamp
                    else phaseEvents.last().timestamp
                val startF = ((ev.timestamp - windowStart).toFloat() / windowSpan).coerceIn(0f, 1f)
                val endF = ((nextT - windowStart).toFloat() / windowSpan).coerceIn(0f, 1f)
                if (endF > startF) {
                    val label = phaseLabelFor(ev.eventType)
                    segs.add(RibbonSegment(
                        label, PhaseColors.saturated(label) ?: phaseColors[ev.eventType]
                            ?: fallbackPrimary,
                        startF, endF))
                }
            }
            segs.ifEmpty {
                fallback.map { (label, type, r) ->
                    RibbonSegment(label, PhaseColors.saturated(label) ?: phaseColors[type]
                        ?: fallbackPrimary, r.first, r.second)
                }
            }
        } else {
            fallback.map { (label, type, r) ->
                RibbonSegment(label, PhaseColors.saturated(label) ?: phaseColors[type]
                    ?: fallbackPrimary, r.first, r.second)
            }
        }
    }

    val samples = remember(events, checkins, windowStart) {
        val pts = mutableListOf<Point<Float, Float>>()
        events.filter { it.intensity != null }
            .forEach { pts.add(DefaultPoint(toMin(it.timestamp), it.intensity!!)) }
        checkins.forEach { pts.add(DefaultPoint(toMin(it.timestamp), it.overallIntensity)) }
        pts.sortedBy { it.x }
    }
    val dense = remember(samples, spanMin) { densify(samples, (spanMin / 120f).coerceAtLeast(1f)) }

    val doseMarks = remember(doses, samples, windowStart, subColors, substanceNameMap) {
        doses.map { dose ->
            val x = toMin(dose.timestamp)
            val name = substanceNameMap[dose.substanceId] ?: dose.substanceId
            Triple(x, intensityAt(samples, x, 8f), subColors[name] ?: fallbackPrimary)
        }
    }
    // Precomputed point triples and color lookup: the symbol lambda below runs
    // per frame, so it must not scan the mark list with minByOrNull.
    val dosePoints = remember(doseMarks) {
        doseMarks.map { (x, y, _) -> DefaultPoint(x, y) }
    }
    val doseColorByX = remember(doseMarks) {
        doseMarks.associate { it.first to it.third }
    }

    val primary = MaterialTheme.colorScheme.primary
    val faintGrid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val labelCol = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f)
    var cursorX by remember { mutableStateOf<Float?>(null) }

    val subtitle = buildString {
        append("T+ from session start")
        if (shulginRating != null) append(" · $shulginRating")
    }

    ChartCard(title = "Session Timeline", subtitle = subtitle) {
        // Phase ribbon: saturated segments, labels hidden when too narrow to read.
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
        ) {
            ribbonSegments.forEach { seg ->
                val weight = (seg.endFrac - seg.startFrac).coerceAtLeast(0.001f)
                Box(
                    modifier = Modifier.weight(weight).height(26.dp)
                        .background(seg.color),
                    contentAlignment = Alignment.Center,
                ) {
                    if (weight > 0.12f) {
                        Text(seg.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = foregroundFor(seg.color))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (samples.isEmpty() && doseMarks.isEmpty()) {
            Text("Log check-ins to trace intensity over time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val xModel = rememberFloatLinearAxisModel(
                0f..spanMin,
                minimumMajorTickIncrement = (axisStepMs(windowSpan) / 60_000f)
                    .coerceAtLeast(1f),
                minimumMajorTickSpacing = 44.dp,
                minorTickCount = 3,
            )
            val yModel = rememberFloatLinearAxisModel(
                0f..10f, minimumMajorTickIncrement = 2f,
                minimumMajorTickSpacing = 22.dp, minorTickCount = 1,
            )
            // Tap/drag anywhere on the plot inspects that moment.
            XYGraph(
                xAxisModel = xModel,
                yAxisModel = yModel,
                xAxisContent = rememberAxisContent(
                    labels = { v ->
                        Text(axisLabel((v * 60_000).toLong()),
                            color = labelCol, fontSize = 10.sp)
                    },
                ),
                yAxisContent = rememberAxisContent(
                    labels = { v ->
                        Text(v.toInt().toString(), color = labelCol, fontSize = 10.sp)
                    },
                ),
                gridStyle = rememberGridStyleCompat(faintGrid),
                onPointerEvent = { event ->
                    event.changes.firstOrNull()?.let { change ->
                        if (change.pressed) cursorX = scale(change.position).x
                            .coerceIn(0f, spanMin)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(190.dp),
            ) {
                if (dense.isNotEmpty()) {
                    AreaPlot(
                        data = dense,
                        areaBaseline = AreaBaseline.HorizontalLine(0f),
                        areaStyle = AreaStyle(
                            brush = Brush.verticalGradient(listOf(
                                primary.copy(alpha = 0.32f),
                                primary.copy(alpha = 0.03f))),
                        ),
                        lineStyle = LineStyle(
                            brush = SolidColor(primary), strokeWidth = 2.dp),
                    )
                    // Real samples get dots; interpolated fill stays clean.
                    LinePlot(
                        data = samples,
                        lineStyle = null,
                        symbol = {
                            Box(Modifier.size(7.dp).background(Color.White, CircleShape)
                                .padding(1.5.dp).background(primary, CircleShape))
                        },
                    )
                }
                if (dosePoints.isNotEmpty()) {
                    LinePlot(
                        data = dosePoints,
                        lineStyle = null,
                        symbol = { point ->
                            val markColor = doseColorByX[point.x] ?: primary
                            Box(Modifier.size(11.dp).background(markColor, CircleShape)
                                .padding(2.5.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape))
                        },
                    )
                }
                cursorX?.let { cx ->
                    VerticalLineAnnotation(
                        location = cx,
                        lineStyle = LineStyle(
                            brush = SolidColor(MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.45f)),
                            strokeWidth = 1.dp),
                    )
                }
            }
            cursorX?.let { cx ->
                Spacer(Modifier.height(6.dp))
                Text("T+${axisLabel((cx * 60_000).toLong())} · intensity " +
                        "~${"%.1f".format(intensityAt(samples, cx, 0f))}/10",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            // One legend row: intensity line, then each substance.
            val legendEntries = buildList {
                if (samples.isNotEmpty()) add("Intensity" to primary)
                subColors.forEach { (name, color) -> add(name to color) }
            }
            if (legendEntries.isNotEmpty()) {
                FlowLegend(
                    itemCount = legendEntries.size,
                    symbol = { i ->
                        val (_, color) = legendEntries[i]
                        if (i == 0 && samples.isNotEmpty()) {
                            Box(Modifier.size(width = 18.dp, height = 3.dp)
                                .background(color, RoundedCornerShape(1.5.dp)))
                        } else {
                            Box(Modifier.size(8.dp).background(color, CircleShape))
                        }
                    },
                    label = { i ->
                        Text(legendEntries[i].first,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberGridStyleCompat(
    color: Color,
): io.github.koalaplot.core.xygraph.GridStyle {
    val major = LineStyle(brush = SolidColor(color), strokeWidth = 1.dp)
    return io.github.koalaplot.core.xygraph.rememberGridStyle(
        horizontalMajorStyle = major,
        horizontalMinorStyle = null,
        verticalMajorStyle = null,
        verticalMinorStyle = null,
    )
}

/** Pick a "nice" tick step for the time axis so labels never duplicate or collide. */
internal fun axisStepMs(rangeMs: Long): Long {
    val hours = rangeMs / 3_600_000.0
    return when {
        hours < 0.5 -> 5 * 60_000L
        hours < 1.5 -> 15 * 60_000L
        hours < 4.0 -> 30 * 60_000L
        hours < 8.0 -> 60 * 60_000L
        hours < 16.0 -> 2 * 3_600_000L
        hours < 36.0 -> 4 * 3_600_000L
        else -> 6 * 3_600_000L
    }
}

/** Compact axis label: 15m, 1h, 1h30m. */
internal fun axisLabel(ms: Long): String {
    val totalMin = ms / 60_000L
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h${m}m"
}
