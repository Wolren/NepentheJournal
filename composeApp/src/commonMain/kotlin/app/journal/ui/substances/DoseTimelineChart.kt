package app.journal.ui.substances

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.model.Dose
import app.journal.ui.charts.ChartTheme
import app.journal.ui.charts.integerFormatter
import app.journal.ui.charts.themedStartAxis
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.chartSeriesColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.currentTimeMillis
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import kotlinx.datetime.*

/**
 * Compact dose-timeline column chart for a substance's dose history.
 * Daily totals over the last 60 days - forest column + muted axes
 * matching SessionsTrendChart and the Duration intensity curve.
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
    val tz = TimeZone.currentSystemDefault()
    val isDark = isDarkTheme()
    // Route series derived from the theme accents.
    val routeColors = chartSeriesColors(8)

    val recentDoses = remember(doses) {
        doses.filter { now - it.timestamp < 60L * dayMs }
    }
    val totalDoseLast30 = remember(doses) {
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
                StatItem("Total", "${doses.size}", MaterialTheme.colorScheme.primary)
                StatItem(
                    "30d",
                    "$totalDoseLast30 ${lastDose?.unit ?: "u"}",
                    MaterialTheme.colorScheme.tertiary
                )
                if (lastDose != null) {
                    val daysAgo = (now - lastDose.timestamp) / dayMs
                    StatItem(
                        "Last",
                        if (daysAgo == 0L) "Today" else "${daysAgo}d ago",
                        MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (recentDoses.size >= 3) {
            val sorted = recentDoses.sortedBy { it.timestamp }
            val dailyEntries = remember(sorted) {
                sorted.groupBy { dose ->
                    Instant.fromEpochMilliseconds(dose.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                }
                    .toSortedMap()
                    .entries.map { (date, ds) -> date to ds.sumOf { it.amount } }
            }

            val values = dailyEntries.map { it.second }
            val labels = dailyEntries.map { (date, _) ->
                "${date.month.name.lowercase().take(3).replaceFirstChar { it.uppercase() }} ${date.day}"
            }

            val bottomFormatter = remember(labels) {
                CartesianValueFormatter { _, x, _ ->
                    labels[x.toInt().coerceIn(0, labels.lastIndex)]
                }
            }

            // Adaptive forest accent for this substance, not a generic primary wash
            val accent = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(values) {
                modelProducer.runTransaction { columnModel { series(values.map { it.toDouble() }) } }
            }

            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(
                        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                            rememberLineComponent(
                                fill = Fill(accent),
                                thickness = 6.dp,
                                shape = RoundedCornerShape(3.dp),
                            )
                        ),
                    ),
                    startAxis = themedStartAxis(
                        formatter = CartesianValueFormatter { _, v, _ ->
                            // Show "5 mg" style only when amounts are small integers; keep raw value
                            val iv = v.toInt()
                            if (v == iv.toDouble()) iv.toString() else String.format("%.1f", v)
                        },
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        line = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent(
                            fill = Fill(ChartTheme.axisLineColor()),
                            thickness = 1.dp,
                        ),
                        label = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent(
                            style = androidx.compose.ui.text.TextStyle(
                                color = ChartTheme.labelColor(),
                                fontSize = 10.sp,
                            ),
                        ),
                        tick = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent(
                            fill = Fill(ChartTheme.axisLineColor()),
                            thickness = 1.dp,
                        ),
                        guideline = null,
                        valueFormatter = bottomFormatter,
                        itemPlacer = HorizontalAxis.ItemPlacer.aligned(
                            spacing = {
                                when {
                                    labels.size <= 6 -> 1
                                    labels.size <= 12 -> 2
                                    else -> 3
                                }
                            }
                        ),
                    ),
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
        } else if (recentDoses.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recentDoses.sortedByDescending { it.timestamp }.forEach { dose ->
                    val local = Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(tz)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${local.month.name.take(3)} ${local.day}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${dose.amount} ${dose.unit} ${dose.routeOfAdministration}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
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

@Composable
private fun StatItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = color
        )
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

