package app.journal.ui.substances

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import kotlinx.datetime.*

/**
 * Compact dose-timeline column chart for a substance's dose history.
 * Shows dose amounts over the last 60 days, columns colored by route.
 * Falls back to a text summary if there's too little data for a chart.
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
    val isDark = ThemeManager.instance.isDarkTheme()

    // Doses in the last 60 days
    val recentDoses = remember(doses) {
        doses.filter { now - it.timestamp < 60L * dayMs }
    }

    val totalDoseLast30 = remember(doses) {
        doses.filter { now - it.timestamp < 30L * dayMs }.sumOf { it.amount }
    }
    val lastDose = remember(doses) {
        doses.maxByOrNull { it.timestamp }
    }

    // Stats row: count, amount last 30d, last dose
    Column(modifier = modifier.fillMaxWidth()) {
        // Summary stats row
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total", "${doses.size}", MaterialTheme.colorScheme.primary)
                StatItem("30d", "$totalDoseLast30 ${lastDose?.unit ?: "u"}",
                    MaterialTheme.colorScheme.tertiary)
                if (lastDose != null) {
                    val tz = TimeZone.currentSystemDefault()
                    val lastLocal = Instant.fromEpochMilliseconds(lastDose.timestamp).toLocalDateTime(tz)
                    val daysAgo = (now - lastDose.timestamp) / dayMs
                    StatItem("Last",
                        if (daysAgo == 0L) "Today" else "${daysAgo}d ago",
                        MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (recentDoses.size >= 3) {
            // Sort by timestamp for the chart
            val sorted = recentDoses.sortedBy { it.timestamp }

            // Group doses by date
            val dailyDoses = remember(sorted) {
                sorted.groupBy { dose ->
                    Instant.fromEpochMilliseconds(dose.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                }.mapValues { (_, doses) -> doses.sumOf { it.amount } }
            }

            val values = dailyDoses.values.toList()
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(values) {
                modelProducer.runTransaction {
                    columnModel { series(values.map { it.toDouble() }) }
                }
            }

            val primaryColor = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)

            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom()
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
        } else if (recentDoses.isNotEmpty()) {
            // Too few doses for a chart — show a simple list
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recentDoses.sortedByDescending { it.timestamp }.forEach { dose ->
                    val tz = TimeZone.currentSystemDefault()
                    val local = Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(tz)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${local.month.name.take(3)} ${local.day}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${dose.amount} ${dose.unit} ${dose.routeOfAdministration}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        } else {
            Text("No doses in the last 60 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Route distribution bar
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
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(fraction),
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
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(route, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${count}x", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (routes.size > 4) {
                    Text("+${routes.size - 4}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val routeColors = listOf(
    Color(0xFFDCA2F4), Color(0xFF4CAF50), Color(0xFF2196F3),
    Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF9C27B0),
    Color(0xFF00BCD4), Color(0xFF8BC34A)
)
