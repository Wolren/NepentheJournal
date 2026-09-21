package app.journal.ui.substances.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.ui.charts.ChartTheme
import app.journal.ui.theme.AdaptiveColors
import app.journal.util.currentTimeMillis

/** Strips wiki markup like [[Target|Display]] or [[Target]] from a string. */
internal fun cleanWikiMarkup(text: String): String {
    return text.replace(Regex("""\[\[([^|\]]+)\|([^\]]+)\]\]""")) { it.groupValues[2] }
        .replace(Regex("""\[\[([^\]]+)\]\]""")) { it.groupValues[1] }
}

@Composable
internal fun ToleranceTimelineSection(doses: List<Dose>, substanceName: String, isDark: Boolean) {
    val now = currentTimeMillis()
    val dayMs = 86400000L
    val lookbackDays = 90
    val sorted = doses.sortedBy { it.timestamp }
    if (sorted.isEmpty()) return

    SectionCard(title = "Tolerance Timeline (90 days)") {
        val lineColor = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)
        val axisLine = ChartTheme.axisLineColor()
        val grid = ChartTheme.gridColorFaint()
        val windowDoses = remember(sorted, now) {
            sorted.filter { it.timestamp > now - lookbackDays * dayMs }
        }
        if (windowDoses.isEmpty()) {
            Text("No doses in the last 90 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
        // Normalize bar height to the largest dose in the window: a fixed
        // denominator flattens every substance (20 mg vs 5000 mg scales).
        val windowMax = remember(windowDoses) {
            windowDoses.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1.0
        }
        val peakUnit = remember(windowDoses) {
            windowDoses.groupBy { it.unit }.maxByOrNull { it.value.size }?.key.orEmpty()
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width
            val h = size.height
            val top = 8.dp.toPx()
            val bottom = h - 2.dp.toPx()
            val plotH = bottom - top
            val start = now - lookbackDays * dayMs

            // Month gridlines plus baseline.
            for (day in listOf(0, 30, 60, 90)) {
                val x = (day.toFloat() / lookbackDays) * w
                drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 0.5f)
            }
            drawLine(axisLine, Offset(0f, bottom), Offset(w, bottom), strokeWidth = 1.5f)

            windowDoses.forEach { dose ->
                val x = ((dose.timestamp - start).toFloat() / (lookbackDays * dayMs)) * w
                val relHeight = (dose.amount / windowMax).coerceIn(0.06, 1.0).toFloat() * plotH
                val yTop = bottom - relHeight
                drawLine(lineColor, Offset(x, bottom), Offset(x, yTop),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x, yTop))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("90d ago", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("60d", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("30d", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text("Peak $windowMax $peakUnit in the last 90 days. Taller lines mean larger doses.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
