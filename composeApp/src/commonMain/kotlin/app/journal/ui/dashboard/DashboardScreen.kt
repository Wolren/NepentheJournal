package app.journal.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.model.ToleranceCalculator
import app.journal.model.ToleranceInfo
import app.journal.model.ToleranceLevel
import app.journal.util.currentTimeMillis
import app.journal.util.formatDateShort
import app.journal.ui.dashboard.ActivityHeatmap
import app.journal.ui.dashboard.SessionsTrendChart
import app.journal.ui.dashboard.TopSubstancesChart
import kotlinx.datetime.*
import app.journal.ui.components.*
import app.journal.ui.theme.ThemeManager

@Composable
fun DashboardScreen(
    repo: IJournalRepository = JournalRepository.instance,
    onSearchClick: () -> Unit = {},
) {
    val sessions by repo.sessions.collectAsState()
    val useRelativeTime by remember { mutableStateOf(true) }
    val doses by repo.doses.collectAsState()
    val substances by repo.substances.collectAsState()
    val showTrendChart by repo.showSessionsTrendChart.collectAsState()
    val persons by repo.persons.collectAsState()

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
    var showProfileEditor by remember { mutableStateOf(false) }
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
                Text(greeting.uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    letterSpacing = 0.08.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nepenthe Journal", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.01).sp,
                        modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Search, contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(22.dp).clickable(onClick = onSearchClick)
                    )
                }
                Text(
                    formatDateShort(today),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 0.06.sp
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

            if (persons.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(Modifier.padding(14.dp).fillMaxWidth()) {
                            Text("Create your profile",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Trips belong to individuals. Add your profile so doses, " +
                                    "timelines and exports carry the right demographics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { showProfileEditor = true },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Create profile") }
                        }
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(14.dp).fillMaxWidth()) {
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

    if (showProfileEditor) {
        PersonEditorDialog(
            initial = null,
            onDismiss = { showProfileEditor = false },
            dialogTitle = "Create profile",
            onSave = { person ->
                repo.upsertPerson(person.copy(isSelf = true))
                showProfileEditor = false
            }
        )
    }
}

private data class DaySubstanceInfo(val date: LocalDate, val count: Int, val substances: List<String>)

@Composable
private fun StatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    val accent = MaterialTheme.colorScheme.primary
    Card(modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Quiet editorial accent - thin rule, not a circled badge
            Box(Modifier.width(24.dp).height(1.5.dp).background(accent.copy(alpha = 0.55f), RoundedCornerShape(1.dp)))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).sp)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                letterSpacing = 0.07.sp)
        }
    }
}

@Composable
private fun ToleranceCard(info: ToleranceInfo) {
    val isDark = ThemeManager.instance.isDarkTheme()
    val levelColor = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.error
        ToleranceLevel.MEDIUM -> if (isDark) toleranceMediumColor else Color(0xFF9A6700)
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiary
        ToleranceLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val levelBg = when (info.level) {
        ToleranceLevel.HIGH -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ToleranceLevel.MEDIUM -> toleranceMediumColor.copy(alpha = 0.08f)
        ToleranceLevel.LOW -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.18f)
        ToleranceLevel.NONE -> Color.Transparent
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(26.dp).background(levelColor.copy(alpha = 0.10f), CircleShape))
                Surface(modifier = Modifier.width(3.dp).height(36.dp), shape = RoundedCornerShape(1.5.dp), color = levelColor.copy(alpha = 0.85f)) {}
            }
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
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                    if (info.totalDosesLast30Days > 0) Text("${info.totalDosesLast30Days}x in 30d",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
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
