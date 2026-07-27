package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.ui.session.timeline.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTimelineScreen(
    repo: JournalRepository = JournalRepository.instance,
    sessionId: String,
    onBack: () -> Unit,
) {
    val session = remember(sessionId) { repo.getSession(sessionId) }
    val events = remember(sessionId) { repo.eventsForSession(sessionId) }
    val doses = remember(sessionId) { repo.dosesForSession(sessionId) }

    val sortedEvents = remember(events) { events.sortedBy { it.timestamp } }
    val sessionDuration = remember(session) { (session?.endTime ?: currentTimeMillis()) - (session?.startTime ?: 0L) }
    val combinedItems = remember(sortedEvents, sessionDuration, session) {
        val items = mutableListOf<TimelineItem>()
        var currentPhase: String? = null
        val startTime = session?.startTime ?: 0L
        for (event in sortedEvents) {
            val elapsedMs = event.timestamp - startTime
            val phaseLabel = phaseLabel(elapsedMs, sessionDuration)
            if (phaseLabel != null && phaseLabel != currentPhase) {
                currentPhase = phaseLabel
                items.add(TimelineItem.PhaseHeader(phaseLabel))
            }
            items.add(TimelineItem.Event(event))
        }
        items
    }

    var showAddEventDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Session not found") }
        return
    }

    ScreenScaffold(
        title = session.title,
        onBack = onBack,
        actions = {
            val vaultPath by repo.obsidianVaultPath.collectAsState()
            val subfolder by repo.obsidianSubfolder.collectAsState()
            if (vaultPath.isNotBlank()) {
                var exportStatus by remember { mutableStateOf<String?>(null) }
                IconButton(onClick = {
                    val config = app.journal.export.obsidian.ObsidianExportConfig(vaultPath = vaultPath, subfolder = subfolder)
                    val path = app.journal.export.obsidian.ObsidianExportManager.exportSession(repo, sessionId, config)
                    exportStatus = if (path != null) "Exported" else "Export failed"
                }) {
                    Icon(Icons.Default.MenuBook, contentDescription = "Export to Obsidian",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) {
        // Session header with rating
        item {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    val tz = TimeZone.currentSystemDefault()
                    val local = Instant.fromEpochMilliseconds(session.startTime).toLocalDateTime(tz)
                    Text("${local.day.toString().padStart(2,'0')} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${local.year}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RatingBadge(session.rating, session.shulginRating)
            }
        }

        // Visual timeline bar
        item { TimelineBar(startTime = session.startTime, endTime = session.endTime, events = events, checkins = session.checkins, doses = doses) }

        // Session intention/outcome
        if (!session.intention.isNullOrBlank() || !session.outcome.isNullOrBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (!session.intention.isNullOrBlank()) {
                            Text("Intention", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp)); Text(session.intention, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!session.outcome.isNullOrBlank()) {
                            if (!session.intention.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                            Text("Outcome", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp)); Text(session.outcome, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Set & Setting
        if (!session.set.isNullOrBlank() || !session.setting.isNullOrBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (!session.set.isNullOrBlank()) {
                            Text("Set (Mindset)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp)); Text(session.set, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!session.setting.isNullOrBlank()) {
                            if (!session.set.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                            Text("Setting (Environment)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp)); Text(session.setting, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Timeline events list with phase headers
        if (combinedItems.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Timeline Events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FilledTonalIconButton(onClick = { showAddEventDialog = true }) {
                        Icon(Icons.Default.Add, "Add event", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            items(combinedItems, key = {
                when (it) { is TimelineItem.Event -> it.event.id; is TimelineItem.PhaseHeader -> "phase_${it.label}" }
            }) { item ->
                when (item) {
                    is TimelineItem.Event -> EventCard(item.event, session.startTime, repo = repo, onDelete = { deletingEvent = it })
                    is TimelineItem.PhaseHeader -> {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(item.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("No timeline events recorded. Add check-ins during a session to build a timeline.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Intensity curve
        item { IntensityCurveOverlay(events = sortedEvents, startTime = session.startTime) }

        // Substances / Dosage Table
        if (doses.isNotEmpty()) {
            item { Text("Substances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { DosageSummaryTable(doses = doses, repo = repo, sessionStart = session.startTime) }
        }

        // Check-in effect tags
        if (session.checkins.any { it.effectScores.isNotEmpty() }) {
            item { EffectTagCloud(session = session, repo = repo) }
        }
    }

    if (showAddEventDialog) { AddEventDialog(session = session, repo = repo, onDismiss = { showAddEventDialog = false }) }
    if (deletingEvent != null) {
        AlertDialog(onDismissRequest = { deletingEvent = null }, title = { Text("Delete event?") },
            text = { Text("Delete \"${deletingEvent?.label}\"? This cannot be undone.") },
            confirmButton = { AppTextButton(onClick = { deletingEvent?.let { repo.deleteTimelineEvent(it.id) }; deletingEvent = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { AppTextButton(onClick = { deletingEvent = null }) { Text("Cancel") } })
    }
}
