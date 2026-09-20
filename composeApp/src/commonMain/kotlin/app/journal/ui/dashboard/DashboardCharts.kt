package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.model.Session
import app.journal.ui.charts.ChartCard
import app.journal.ui.charts.forestLine
import app.journal.ui.charts.integerFormatter
import app.journal.ui.charts.themedStartAxis
import app.journal.ui.theme.chartSeriesColors
import app.journal.util.currentTimeMillis
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import kotlinx.datetime.*

/**
 * Line chart showing sessions per week for the last 12 weeks.
 * Forest line, cubic smoothing, soft fill - same language as the
 * Duration intensity curve (ggplot-grade polish, not Vico defaults).
 */
@Composable
fun SessionsTrendChart(
    sessions: List<Session>,
    modifier: Modifier = Modifier
) {
    if (sessions.isEmpty()) return

    val tz = TimeZone.currentSystemDefault()
    val nowMillis = currentTimeMillis()
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date

    val weekCounts = remember(sessions, now) {
        val todayEpoch = now.toEpochDays()
        val mondayEpoch = todayEpoch - (now.dayOfWeek.isoDayNumber - 1)
        val counts = MutableList(12) { 0 }
        sessions.forEach { session ->
            val date = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(tz).date
            val dateEpoch = date.toEpochDays()
            val sessionMonEpoch = dateEpoch - (date.dayOfWeek.isoDayNumber - 1)
            val weekDiff = ((sessionMonEpoch - mondayEpoch) / 7).toInt()
            val idx = 11 + weekDiff
            if (idx in counts.indices) counts[idx] = counts[idx] + 1
        }
        counts.map { it.toDouble() }
    }

    if (weekCounts.all { it == 0.0 }) return

    // Monday label for each of the 12 buckets: "Jan 6", "Jan 13" …
    val weekLabels = remember(now) {
        val todayEpoch = now.toEpochDays()
        val mondayEpoch = todayEpoch - (now.dayOfWeek.isoDayNumber - 1)
        List(12) { idx ->
            val monday = LocalDate.fromEpochDays(mondayEpoch - (11 - idx) * 7)
            "${monday.month.name.lowercase().take(3).replaceFirstChar { it.uppercase() }} ${monday.day}"
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(weekCounts) {
        modelProducer.runTransaction { lineModel { series(weekCounts) } }
    }

    val weekFormatter = remember(weekLabels) {
        CartesianValueFormatter { _, x, _ ->
            val i = x.toInt().coerceIn(0, weekLabels.lastIndex)
            weekLabels[i]
        }
    }

    ChartCard(
        title = "Sessions per Week",
        subtitle = "Last 12 weeks",
        modifier = modifier,
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(forestLine())
                ),
                startAxis = themedStartAxis(formatter = integerFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(
                    // Show every ~3rd week label so 12 labels do not collide on mobile
                    line = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent(
                        fill = com.patrykandpatrick.vico.compose.common.Fill(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        ),
                        thickness = 1.dp,
                    ),
                    label = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent(
                        style = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                        ),
                    ),
                    tick = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent(
                        fill = com.patrykandpatrick.vico.compose.common.Fill(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        ),
                        thickness = 1.dp,
                    ),
                    guideline = null,
                    valueFormatter = weekFormatter,
                    itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { 3 }),
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

/**
 * Bar chart for top substances by session count.
 * Manual rows - horizontal Vico bars are unreliable on CMP and the
 * hand-built version already matches the DoseTimeline polish.
 * Now wrapped in ChartCard for consistent card shape / header.
 */
@Composable
fun TopSubstancesChart(
    substanceSessionPairs: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    if (substanceSessionPairs.isEmpty()) return

    val ranked = remember(substanceSessionPairs) {
        substanceSessionPairs.groupBy { it.first }
            .mapValues { (_, pairs) -> pairs.distinctBy { it.second }.size }
            .entries.sortedByDescending { it.value }.take(8)
    }
    if (ranked.isEmpty()) return
    val maxCount = ranked.firstOrNull()?.value?.toFloat() ?: 1f

    // Series derived from the theme accents, so custom palettes recolor the chart.
    val chartColors = chartSeriesColors(8)

    ChartCard(
        title = "Top Substances",
        subtitle = "${ranked.size} substances",
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ranked.forEachIndexed { index, (name, count) ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fraction = count.toFloat() / maxCount
                    androidx.compose.material3.Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.weight(2f).height(14.dp)
                    ) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(0.05f))
                                .fillMaxHeight(),
                            color = chartColors[index % chartColors.size],
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                        ) {}
                    }
                    androidx.compose.material3.Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = chartColors[index % chartColors.size],
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
    }
}
