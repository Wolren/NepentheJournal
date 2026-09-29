package app.journal.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.journal.data.ToleranceLevel
import app.journal.data.toleranceLevelFor
import app.journal.model.Dose
import app.journal.model.Substance
import app.journal.ui.components.toleranceMediumColor
import app.journal.ui.theme.isDarkTheme
import app.journal.util.formatDateShort
import kotlin.math.min

/** Default window: the 30 days before the session, like the reference. */
internal const val TOLERANCE_WINDOW_DAYS = 30

/** Horizontal run of days at one tolerance level. Day indices are end-exclusive. */
internal data class ToleranceSegment(val startDay: Int, val endDay: Int, val level: ToleranceLevel)

/** One substance's row: its name plus every segment drawn on it. */
internal data class ToleranceChartRow(
    val substanceId: String,
    val name: String,
    val segments: List<ToleranceSegment>,
)

/**
 * Rebuild tolerance level day by day over the [days] ending at [hereMs], and
 * merge equal days into segments.
 *
 * This deliberately does *not* call [app.journal.data.ToleranceCalculator] per
 * day: that path resolves every substance through the repository (a lock
 * acquire each) and has no as-of bound, so replaying it per day would both
 * block and leak doses from after [hereMs]. Instead the doses are read once,
 * capped at [hereMs] so the future never leaks in, and the shared
 * [toleranceLevelFor] rule is reapplied at each day end - the same rule the
 * calculator uses, so the two can't disagree.
 *
 * The lookback is [days] + 30: the oldest day in the window still needs the
 * 30 days before it to count "doses in the last 30 days" for the HIGH branch.
 * Substances with no active tolerance in the window are omitted, which keeps
 * the chart to the rows that actually mean something.
 */
internal fun buildToleranceRows(
    doses: List<Dose>,
    hereMs: Long,
    substancesById: Map<String, Substance>,
    days: Int = TOLERANCE_WINDOW_DAYS,
): List<ToleranceChartRow> {
    val dayMs = 86_400_000L
    val windowStart = hereMs - days * dayMs

    val eligible = doses
        .filter { it.timestamp > hereMs - (days + 30L) * dayMs && it.timestamp <= hereMs }
        .groupBy { it.substanceId }
        .mapValues { (_, group) -> group.map { it.timestamp }.sorted() }
    if (eligible.isEmpty()) return emptyList()

    val rows = eligible.mapNotNull { (substanceId, sortedTimestamps) ->
        val segments = mutableListOf<ToleranceSegment>()
        for (day in 0 until days) {
            val dayEnd = min(windowStart + (day + 1L) * dayMs - 1, hereMs)
            val lastDoseMs = sortedTimestamps.lastOrNull { it <= dayEnd } ?: continue
            val daysSince = (dayEnd - lastDoseMs) / dayMs.toDouble()
            val dosesLast30 = sortedTimestamps.count { dayEnd - it < 30L * dayMs }
            val level = toleranceLevelFor(daysSince, dosesLast30)
            if (level == ToleranceLevel.NONE) continue

            val last = segments.lastOrNull()
            if (last != null && last.endDay == day && last.level == level) {
                segments[segments.lastIndex] = last.copy(endDay = day + 1)
            } else {
                segments.add(ToleranceSegment(startDay = day, endDay = day + 1, level = level))
            }
        }
        if (segments.isEmpty()) return@mapNotNull null
        ToleranceChartRow(
            substanceId = substanceId,
            name = substancesById[substanceId]?.name ?: substanceId,
            segments = segments,
        )
    }.sortedByDescending { row -> row.segments.maxOf { it.endDay } }

    return rows.take(6)
}

/**
 * Color for a tolerance level, shared by the bars and any legend drawn next
 * to them so the two can never drift apart.
 */
@Composable
internal fun toleranceLevelColor(level: ToleranceLevel): Color = when (level) {
    ToleranceLevel.HIGH -> MaterialTheme.colorScheme.error
    ToleranceLevel.MEDIUM -> if (isDarkTheme()) toleranceMediumColor else Color(0xFF9A6700)
    ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiary
    ToleranceLevel.NONE -> Color.Unspecified
}

/**
 * Tolerance history bars in the PsychonautWiki Journal style: one row per
 * substance, days merged into rounded segments colored by level, and a
 * marker line at [hereMs]. With [showRowLabels] the substance names sit in a
 * fixed left gutter and the plot shares the rest; without it the plot takes
 * the full width, which is what a single-substance card wants.
 */
@Composable
internal fun ToleranceBarsChart(
    rows: List<ToleranceChartRow>,
    hereMs: Long,
    modifier: Modifier = Modifier,
    days: Int = TOLERANCE_WINDOW_DAYS,
    showRowLabels: Boolean = true,
    rowHeight: Dp = 26.dp,
) {
    if (rows.isEmpty()) return

    val levelColors = listOf(ToleranceLevel.HIGH, ToleranceLevel.MEDIUM, ToleranceLevel.LOW)
        .associateWith { toleranceLevelColor(it) }
    val hereColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val nameColor = MaterialTheme.colorScheme.onSurface

    val dayMs = 86_400_000L
    val windowStart = hereMs - days * dayMs
    // Dates every seventh day, then the end marker pinned to the right edge
    // where the line is - the reference labels both ends the same way.
    val labelStep = days / 4
    val labels = buildList {
        var day = 0
        while (day < days - labelStep) {
            add(
                TickLabel(
                    fraction = day.toFloat() / days,
                    text = formatDateShort(windowStart + day * dayMs),
                )
            )
            day += labelStep
        }
        add(TickLabel(fraction = 1f, text = "Here", emphasis = true))
    }

    val canvas: @Composable () -> Unit = {
        Canvas(
            Modifier.fillMaxWidth().height(rowHeight * rows.size),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val rowPx = rowHeight.toPx()
            val inset = 5.dp.toPx()
            val radius = 3.dp.toPx()
            val minWidth = 3.dp.toPx()

            rows.forEachIndexed { index, row ->
                val yTop = index * rowPx + inset
                val barHeight = rowPx - inset * 2
                row.segments.forEach { segment ->
                    val color = levelColors[segment.level] ?: return@forEach
                    val x0 = segment.startDay.toFloat() / days * w
                    val x1 = segment.endDay.toFloat() / days * w
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x0, yTop),
                        size = Size((x1 - x0).coerceAtLeast(minWidth), barHeight),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
            }

            // The reference moment ("here"/"today"), at the right edge.
            val hereX = w - 1.dp.toPx() / 2f
            drawLine(
                color = hereColor,
                start = Offset(hereX, 0f),
                end = Offset(hereX, h),
                strokeWidth = 1.5.dp.toPx(),
            )
        }

        Spacer(Modifier.height(4.dp))
        TickLabelRow(labels)
    }

    if (showRowLabels) {
        Row(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.width(84.dp)) {
                rows.forEach { row ->
                    Box(
                        Modifier.fillMaxWidth().height(rowHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = nameColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) { canvas() }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) { canvas() }
    }
}
