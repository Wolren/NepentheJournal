package app.journal.ui.substances

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.ui.charts.ChartTheme
import app.journal.ui.charts.TickLabel
import app.journal.ui.charts.TickLabelRow
import app.journal.ui.components.StatItem
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.chartSeriesColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.currentTimeMillis
import app.journal.util.formatDateShort
import app.journal.util.formatDoseAmount

/**
 * Ingestion history for a substance: every dose in the last 60 days as a stick
 * on a baseline - height scaled to the largest dose in the window - with the
 * date axis drawn on both sides of the plot, the way PsychonautWiki Journal
 * frames its timelines. Stats and the route split stay as they were.
 */
@Composable
fun DoseTimelineChart(
    doses: List<Dose>,
    substanceName: String,
    modifier: Modifier = Modifier
) {
    if (doses.isEmpty()) return

    val now = currentTimeMillis()
    val dayMs = 86400000L
    val isDark = isDarkTheme()
    // Route series derived from the theme accents.
    val routeColors = chartSeriesColors(8)

    val recentDoses = remember(doses, now) {
        doses.filter { now - it.timestamp < 60L * dayMs }
    }
    val totalDoseLast30 = remember(doses, now) {
        doses.filter { now - it.timestamp < 30L * dayMs }.sumOf { it.amount }
    }
    val lastDose = remember(doses) { doses.maxByOrNull { it.timestamp } }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total", "${doses.size}", valueStyle = MaterialTheme.typography.titleSmall, valueColor = MaterialTheme.colorScheme.primary)
                StatItem(
                    "30d",
                    "$totalDoseLast30 ${lastDose?.unit ?: "u"}",
                    valueStyle = MaterialTheme.typography.titleSmall,
                    valueColor = MaterialTheme.colorScheme.tertiary
                )
                if (lastDose != null) {
                    val daysAgo = (now - lastDose.timestamp) / dayMs
                    StatItem(
                        "Last",
                        if (daysAgo == 0L) "Today" else "${daysAgo}d ago",
                        valueStyle = MaterialTheme.typography.titleSmall,
                        valueColor = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (recentDoses.isNotEmpty()) {
            // Adaptive accent for this substance, not a generic primary wash.
            val accent = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)
            // Normalize stick height to the largest dose in the window: a fixed
            // denominator flattens every substance (20 mg vs 5000 mg scales).
            val windowMax = remember(recentDoses) {
                recentDoses.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1.0
            }
            val peakUnit = remember(recentDoses) {
                recentDoses.groupBy { it.unit }.maxByOrNull { it.value.size }?.key.orEmpty()
            }
            val startMs = now - 60L * dayMs
            val labels = listOf(
                TickLabel(0f, formatDateShort(startMs)),
                TickLabel(1f / 3f, formatDateShort(startMs + 20L * dayMs)),
                TickLabel(2f / 3f, formatDateShort(startMs + 40L * dayMs)),
                TickLabel(1f, "Today", emphasis = true),
            )

            val axisLine = ChartTheme.axisLineColor()
            TickLabelRow(labels)
            Spacer(Modifier.height(3.dp))
            Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas
                val bottom = h - 2.dp.toPx()
                val top = 8.dp.toPx()
                val plotH = bottom - top

                drawLine(
                    axisLine,
                    Offset(0f, bottom),
                    Offset(w, bottom),
                    strokeWidth = 1.5f,
                )

                recentDoses.sortedBy { it.timestamp }.forEach { dose ->
                    val x = ((dose.timestamp - startMs).toFloat() / (60f * dayMs)) * w
                    val relHeight = (dose.amount / windowMax).coerceIn(0.06, 1.0).toFloat() * plotH
                    val yTop = bottom - relHeight
                    drawLine(
                        accent,
                        Offset(x, bottom),
                        Offset(x, yTop),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(accent, radius = 3.dp.toPx(), center = Offset(x, yTop))
                }
            }
            Spacer(Modifier.height(3.dp))
            TickLabelRow(labels)
            Spacer(Modifier.height(4.dp))
            Text(
                "Taller lines mean larger doses - peak ${formatDoseAmount(windowMax)} $peakUnit in the last 60 days.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "No doses in the last 60 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val routes = remember(doses) {
            doses.groupBy { it.routeOfAdministration.lowercase() }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
        }
        if (routes.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(4.dp)) {
                val total = routes.sumOf { it.value }.toFloat()
                routes.forEachIndexed { i, (_, count) ->
                    val fraction = count / total
                    Surface(
                        modifier = Modifier.fillMaxHeight().weight(fraction),
                        color = routeColors[i % routeColors.size],
                        shape = if (i == 0) RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
                        else if (i == routes.lastIndex) RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
                        else RoundedCornerShape(0.dp)
                    ) {}
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                routes.take(4).forEach { (route, count) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            route, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${count}x", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (routes.size > 4) {
                    Text(
                        "+${routes.size - 4}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
