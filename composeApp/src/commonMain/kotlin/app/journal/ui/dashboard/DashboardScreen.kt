package app.journal.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.journal.ui.dashboard.SessionsTrendChart
import app.journal.ui.dashboard.TopSubstancesChart
import kotlinx.datetime.*
import app.journal.ui.components.*

@Composable
fun DashboardScreen(
    repo: JournalRepository = JournalRepository.instance,
    onSearchClick: () -> Unit = {},
) {
    val sessions by repo.sessions.collectAsState()
    val useRelativeTime by remember { mutableStateOf(true) }
    val doses by repo.doses.collectAsState()
    val substances by repo.substances.collectAsState()
    val showTrendChart by repo.showSessionsTrendChart.collectAsState()

    val toleranceVersion by repo.toleranceVersion.collectAsState()
    val calculator = remember { ToleranceCalculator(repo) }
    val toleranceAll = remember(toleranceVersion) { calculator.calculate() }
    val toleranceList = remember(toleranceAll) {
        val nonNone = toleranceAll.filter { it.level != ToleranceLevel.NONE }
        val noneCapped = toleranceAll.filter { it.level == ToleranceLevel.NONE }.take(5)
        nonNone + noneCapped
    }
    val totalSessions = sessions.size
    val totalSubstances = substances.size

    val substanceSessionPairs = remember(doses, substances) {
        doses.mapNotNull { dose ->
            val sub = repo.getSubstance(dose.substanceId)
            if (sub != null) sub.name to dose.sessionId else null
        }
    }

    val substancesByDate = remember(doses, repo) {
        val map = mutableMapOf<LocalDate, MutableSet<String>>()
        val tz = TimeZone.currentSystemDefault()
        for (dose in doses) {
            val date = Instant.fromEpochMilliseconds(dose.timestamp)
                .toLocalDateTime(tz).date
            val name = repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId
            map.getOrPut(date) { mutableSetOf() }.add(name)
        }
        map.mapValues { (_, names) -> names.sorted() }
    }

    var clickedDayInfo by remember { mutableStateOf<DaySubstanceInfo?>(null) }
    val scrollState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = scrollState
        ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nepenthe Journal", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Search, contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp).clickable(onClick = onSearchClick)
                    )
                }
                Text(
                    formatDate(today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                                nowMillis = currentTimeMillis(),
                                onCellClick = { date, count ->
                                    val names = substancesByDate[date]
                                    clickedDayInfo = if (names != null) {
                                        DaySubstanceInfo(date = date, count = names.size, substances = names)
                                    } else {
                                        DaySubstanceInfo(date = date, count = 0, substances = emptyList())
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (sessions.isNotEmpty() && showTrendChart) {
                item { SessionsTrendChart(sessions = sessions, modifier = Modifier.fillMaxWidth()) }
            }

            if (substanceSessionPairs.isNotEmpty()) {
                item { TopSubstancesChart(substanceSessionPairs = substanceSessionPairs) }
            }

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
                itemsIndexed(toleranceList, key = { _, info -> info.substanceId }) { _, info ->
                    ToleranceCard(info)
                }
            } else {
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

        DesktopScrollbar(scrollState)
    }

    clickedDayInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { clickedDayInfo = null },
            title = { Text(formatDateShort(info.date), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${info.count} substance${if (info.count != 1) "s" else ""} taken",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.substances.isNotEmpty()) { Divider(); info.substances.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            },
            confirmButton = { TextButton(onClick = { clickedDayInfo = null }) { Text("OK") } },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

private data class DaySubstanceInfo(val date: LocalDate, val count: Int, val substances: List<String>)

@Composable
private fun StatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToleranceCard(info: ToleranceInfo) {
    val levelColor = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.error
        ToleranceLevel.MEDIUM -> Color(0xFFFF9800)
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiary
        ToleranceLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val levelBg = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ToleranceLevel.MEDIUM -> Color(0xFFFF9800).copy(alpha = 0.15f)
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        ToleranceLevel.NONE -> Color.Transparent
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.width(4.dp).height(48.dp), shape = RoundedCornerShape(2.dp), color = levelColor) {}
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.substanceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    ToleranceBadge(info.level, levelColor, levelBg)
                }
                Spacer(Modifier.height(2.dp))
                Text("Last: ${info.lastDoseAmount} ${info.lastDoseUnit} (${info.lastDoseRoute})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeSinceLabel(info.hoursSinceLastDose, info.daysSinceLastDose),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.totalDosesLast30Days > 0) Text("${info.totalDosesLast30Days}x in 30d",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToleranceBadge(level: ToleranceLevel, color: Color, bg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(when (level) { ToleranceLevel.HIGH -> "HIGH"; ToleranceLevel.MEDIUM -> "MED"; ToleranceLevel.LOW -> "LOW"; ToleranceLevel.NONE -> "NONE" },
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun timeSinceLabel(hours: Long, days: Double): String = when {
    hours < 1 -> "Less than an hour ago"; hours < 24 -> "${hours}h ago"; days < 2 -> "Yesterday"
    days < 7 -> "${days.toInt()} days ago"; days < 30 -> "${(days / 7).toInt()} weeks ago"
    days < 365 -> "${(days / 30).toInt()} months ago"; else -> "${(days / 365).toInt()} years ago"
}

private fun formatDate(local: LocalDateTime): String {
    val dow = when (local.dayOfWeek) { DayOfWeek.MONDAY -> "Monday"; DayOfWeek.TUESDAY -> "Tuesday"; DayOfWeek.WEDNESDAY -> "Wednesday"; DayOfWeek.THURSDAY -> "Thursday"; DayOfWeek.FRIDAY -> "Friday"; DayOfWeek.SATURDAY -> "Saturday"; DayOfWeek.SUNDAY -> "Sunday" }
    val month = when (local.month) { Month.JANUARY -> "January"; Month.FEBRUARY -> "February"; Month.MARCH -> "March"; Month.APRIL -> "April"; Month.MAY -> "May"; Month.JUNE -> "June"; Month.JULY -> "July"; Month.AUGUST -> "August"; Month.SEPTEMBER -> "September"; Month.OCTOBER -> "October"; Month.NOVEMBER -> "November"; Month.DECEMBER -> "December"; else -> "Unknown" }
    return "$dow, $month ${local.day}, ${local.year}"
}

private fun formatDateShort(date: LocalDate): String {
    val monthAbbr = when (date.month) { Month.JANUARY -> "Jan"; Month.FEBRUARY -> "Feb"; Month.MARCH -> "Mar"; Month.APRIL -> "Apr"; Month.MAY -> "May"; Month.JUNE -> "Jun"; Month.JULY -> "Jul"; Month.AUGUST -> "Aug"; Month.SEPTEMBER -> "Sep"; Month.OCTOBER -> "Oct"; Month.NOVEMBER -> "Nov"; Month.DECEMBER -> "Dec"; else -> "???" }
    return "${date.day} $monthAbbr ${date.year}"
}
