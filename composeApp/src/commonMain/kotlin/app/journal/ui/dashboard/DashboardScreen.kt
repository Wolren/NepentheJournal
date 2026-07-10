package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.ToleranceCalculator
import app.journal.model.ToleranceInfo
import app.journal.model.ToleranceLevel
import app.journal.util.currentTimeMillis
import app.journal.ui.dashboard.ActivityHeatmap
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.toLocalDateTime
import app.journal.ui.components.*

@Composable
fun DashboardScreen() {
    val repo = remember { JournalRepository.instance }
    val sessions by repo.sessions.collectAsState()
    val doses by repo.doses.collectAsState()
    val substances by repo.substances.collectAsState()

    // Tolerance derived from dose data — uses the same dose state so only
    // one recomposition when doses change (not double from toleranceVersion).
    val toleranceAll = remember(doses, substances) { ToleranceCalculator.calculate(repo) }
    val toleranceList = remember(toleranceAll) {
        // Cap NONE-level items to 5 to avoid endless scrolling
        val nonNone = toleranceAll.filter { it.level != ToleranceLevel.NONE }
        val noneCapped = toleranceAll.filter { it.level == ToleranceLevel.NONE }.take(5)
        nonNone + noneCapped
    }
    val totalSessions = sessions.size
    val totalSubstances = substances.size

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Greeting + date
        item {
            val tz = TimeZone.currentSystemDefault()
            val now = Instant.fromEpochMilliseconds(currentTimeMillis())
            val today = now.toLocalDateTime(tz)
            val greeting = when (today.hour) {
                in 5..11 -> "Good morning"
                in 12..17 -> "Good afternoon"
                else -> "Good evening"
            }
            Text(greeting, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Nepenthe Journal", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                formatDate(today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Stats row
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), Icons.Default.Nightlight,
                    "Sessions", totalSessions.toString())
                StatCard(Modifier.weight(1f), Icons.Default.Science,
                    "Substances", totalSubstances.toString())
                StatCard(Modifier.weight(1f), Icons.Default.Timeline,
                    "Doses", doses.size.toString())
            }
        }

        // Activity heatmap
        if (sessions.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        val sessionDates = remember(sessions) {
                            sessions.map { it.startTime }
                        }
                        ActivityHeatmap(
                            sessionDates = sessionDates,
                            nowMillis = currentTimeMillis()
                        )
                    }
                }
            }
        }

        // Tolerance header
        if (toleranceList.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tolerance Overview", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("based on last ingestions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items(toleranceList, key = { it.substanceId }) { info ->
                ToleranceCard(info)
            }
        } else {
            // Empty state
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Timeline, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp))
                        Text("No sessions data yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Log a session with substances to track tolerance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToleranceCard(info: ToleranceInfo) {
    val levelColor = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.error
        ToleranceLevel.MEDIUM -> Color(0xFFFF9800) // amber
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiary
        ToleranceLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val levelBg = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ToleranceLevel.MEDIUM -> Color(0xFFFF9800).copy(alpha = 0.15f)
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        ToleranceLevel.NONE -> Color.Transparent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            // Level indicator bar
            Surface(
                modifier = Modifier.width(4.dp).height(48.dp),
                shape = RoundedCornerShape(2.dp),
                color = levelColor
            ) {}

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.substanceName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    ToleranceBadge(info.level, levelColor, levelBg)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Last: ${info.lastDoseAmount} ${info.lastDoseUnit} (${info.lastDoseRoute})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        timeSinceLabel(info.hoursSinceLastDose, info.daysSinceLastDose),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (info.totalDosesLast30Days > 0) {
                        Text(
                            "${info.totalDosesLast30Days}x in 30d",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToleranceBadge(level: ToleranceLevel, color: Color, bg: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            when (level) {
                ToleranceLevel.HIGH -> "HIGH"
                ToleranceLevel.MEDIUM -> "MED"
                ToleranceLevel.LOW -> "LOW"
                ToleranceLevel.NONE -> "NONE"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun timeSinceLabel(hours: Long, days: Double): String {
    return when {
        hours < 1 -> "Less than an hour ago"
        hours < 24 -> "${hours}h ago"
        days < 2 -> "Yesterday"
        days < 7 -> "${days.toInt()} days ago"
        days < 30 -> "${(days / 7).toInt()} weeks ago"
        days < 365 -> "${(days / 30).toInt()} months ago"
        else -> "${(days / 365).toInt()} years ago"
    }
}

private fun formatDate(local: LocalDateTime): String {
    val dow = when (local.dayOfWeek) {
        DayOfWeek.MONDAY -> "Monday"; DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"; DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"; DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }
    val month = when (local.month) {
        Month.JANUARY -> "January"; Month.FEBRUARY -> "February"
        Month.MARCH -> "March"; Month.APRIL -> "April"
        Month.MAY -> "May"; Month.JUNE -> "June"
        Month.JULY -> "July"; Month.AUGUST -> "August"
        Month.SEPTEMBER -> "September"; Month.OCTOBER -> "October"
        Month.NOVEMBER -> "November"; Month.DECEMBER -> "December"
        else -> "Unknown"
    }
    return "$dow, $month ${local.day}, ${local.year}"
}
