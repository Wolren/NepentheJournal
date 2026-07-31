package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Session
import app.journal.util.currentTimeMillis
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import kotlinx.datetime.*

/**
 * Line chart showing sessions per week for the last 12 weeks.
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
        // Monday of current week: subtract dayOfWeek - 1 days
        val mondayEpoch = todayEpoch - (now.dayOfWeek.isoDayNumber - 1)
        val counts = MutableList(12) { 0 }

        sessions.forEach { session ->
            val date = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(tz).date
            val dateEpoch = date.toEpochDays()
            val sessionMonEpoch = dateEpoch - (date.dayOfWeek.isoDayNumber - 1)
            val weekDiff = ((sessionMonEpoch - mondayEpoch) / 7).toInt()
            val idx = 11 + weekDiff
            if (idx in counts.indices) {
                counts[idx] = counts[idx] + 1
            }
        }
        counts.map { it.toDouble() }
    }

    if (weekCounts.all { it == 0.0 }) return

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(weekCounts) {
        modelProducer.runTransaction {
            lineModel { series(weekCounts) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Sessions per Week",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom()
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }
    }
}

/**
 * Bar chart for top substances by session count.
 * Uses manual composable rows — simpler and more readable than
 * attempting horizontal bars with Vico on Compose Multiplatform.
 */
@Composable
fun TopSubstancesChart(
    substanceSessionPairs: List<Pair<String, String>>, // (substanceName, sessionId)
    modifier: Modifier = Modifier
) {
    if (substanceSessionPairs.isEmpty()) return

    val ranked = remember(substanceSessionPairs) {
        substanceSessionPairs.groupBy { it.first }
            .mapValues { (_, pairs) -> pairs.distinctBy { it.second }.size }
            .entries
            .sortedByDescending { it.value }
            .take(8)
    }

    if (ranked.isEmpty()) return
    val maxCount = ranked.firstOrNull()?.value?.toFloat() ?: 1f

    val chartColors = listOf(
        Color(0xFFDCA2F4), Color(0xFFC28DE0), Color(0xFFAD89D6),
        Color(0xFF9C7CBE), Color(0xFF8A6FA6), Color(0xFF786290),
        Color(0xFF66557A), Color(0xFF544864)
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Top Substances",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))

            ranked.forEachIndexed { index, (name, count) ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fraction = count.toFloat() / maxCount
                    // Name: takes the left column
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    // Bar: proportional to count, anchored left of its track
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .height(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(0.05f))
                                .fillMaxHeight(),
                            color = chartColors[index % chartColors.size],
                            shape = RoundedCornerShape(3.dp)
                        ) {}
                    }
                    // Count: fixed width
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = chartColors[index % chartColors.size],
                        modifier = Modifier.width(28.dp)
                    )
                }
            }
        }
    }
}
