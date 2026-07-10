package app.journal.ui.session

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.ClassInteractionChecker
import app.journal.data.ClassBasedWarning
import app.journal.data.InteractionWarningLevel
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Live session mode: a calm, altered-state-friendly workspace for an ongoing
 * experience. Shows elapsed time, one-tap dose/mood logging, interaction
 * warnings, and an always-visible "Get help" button.
 *
 * No LLM dependency — pure UI + data layer. Works on both mobile and desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSessionScreen(
    session: Session,
    onBack: () -> Unit,
) {
    val repo = remember { JournalRepository.instance }
    val listState = rememberLazyListState()

    // Live timer
    var now by remember { mutableStateOf(currentTimeMillis()) }
    var elapsedMs by remember { mutableStateOf(now - session.startTime) }

    LaunchedEffect(session.id) {
        while (true) {
            delay(1000L)
            now = currentTimeMillis()
            elapsedMs = now - session.startTime
        }
    }

    // Data
    val doses by repo.doses.collectAsState()
    val sessionDoses = remember(doses, session.id) {
        doses.filter { it.sessionId == session.id }.sortedBy { it.timestamp }
    }

    val timelineEvents by repo.timelineEvents.collectAsState()
    val sessionEvents = remember(timelineEvents, session.id) {
        timelineEvents.filter { it.sessionId == session.id }.sortedBy { it.timestamp }
    }

    val allSubstances by repo.substances.collectAsState()

    // Interaction warnings
    val usedSubstances = remember(sessionDoses, allSubstances) {
        val ids = sessionDoses.map { it.substanceId }.distinct()
        allSubstances.filter { it.id in ids }
    }
    val warnings = remember(usedSubstances) {
        ClassInteractionChecker.check(usedSubstances)
    }

    // Quick-entry state
    var showDoseDialog by remember { mutableStateOf(false) }
    var showMoodDialog by remember { mutableStateOf(false) }
    var showCrisisDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new events come in
    LaunchedEffect(sessionEvents.size) {
        if (sessionEvents.isNotEmpty() && listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?: 0 >= sessionEvents.size - 2) {
            listState.animateScrollToItem(sessionEvents.size - 1)
        }
    }

    // Dialogs
    if (showDoseDialog) {
        QuickDoseDialog(
            substances = allSubstances,
            session = session,
            repo = repo,
            onDismiss = { showDoseDialog = false },
        )
    }
    if (showMoodDialog) {
        QuickMoodDialog(
            session = session,
            repo = repo,
            onDismiss = { showMoodDialog = false },
        )
    }
    if (showCrisisDialog) {
        CrisisResourcesDialog(
            onDismiss = { showCrisisDialog = false },
        )
    }

    ScreenScaffold(
        title = session.title.ifBlank { "Live Session" },
        onBack = onBack,
        actions = {
            IconButton(onClick = { showCrisisDialog = true }) {
                Icon(Icons.Default.Emergency, contentDescription = "Get help",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    ) {
        // Main timer
        item {
            TimerCard(elapsedMs)
        }

        // Interaction warnings banner
        if (warnings.isNotEmpty()) {
            item {
                InteractionWarningsBanner(warnings)
            }
        }

        // Quick action buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppTonalButton(
                    onClick = { showDoseDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log Dose")
                }
                AppTonalButton(
                    onClick = { showMoodDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("How I Feel")
                }
            }
        }

        // Timeline events
        if (sessionEvents.isNotEmpty()) {
            item {
                Text("Timeline", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            items(sessionEvents.reversed(), key = { it.id }) { event ->
                LiveEventCard(event, session.startTime)
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Text("Session started. Log a dose or check in to begin tracking.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Doses summary
        if (sessionDoses.isNotEmpty()) {
            item {
                Text("Doses", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(sessionDoses.reversed(), key = { it.id }) { dose ->
                val sub = allSubstances.find { it.id == dose.substanceId }
                LiveDoseCard(dose, sub, session.startTime)
            }
        }

        // Crisis button (always visible at bottom)
        item {
            Spacer(Modifier.height(8.dp))
            AppButton(
                onClick = { showCrisisDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                )
            ) {
                Icon(Icons.Default.Emergency, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Get Help Now", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimerCard(elapsedMs: Long) {
    val totalSec = elapsedMs / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    val timeStr = if (hours > 0) {
        "${hours}h ${mins.toString().padStart(2, '0')}m ${secs.toString().padStart(2, '0')}s"
    } else {
        "${mins}m ${secs.toString().padStart(2, '0')}s"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Elapsed Time",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(timeStr,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun InteractionWarningsBanner(warnings: List<ClassBasedWarning>) {
    val dangerWarnings = warnings.filter { it.level == InteractionWarningLevel.DANGER }
    val cautionWarnings = warnings.filter { it.level == InteractionWarningLevel.CAUTION }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dangerWarnings.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFD32F2F).copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Warning, null,
                            tint = Color(0xFFD32F2F), modifier = Modifier.size(18.dp))
                        Text("Dangerous Combinations",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F))
                    }
                    dangerWarnings.forEach { w ->
                        Text(w.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 24.dp, top = 4.dp))
                    }
                }
            }
        }
        if (cautionWarnings.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFF9800).copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Warning, null,
                            tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                        Text("Caution",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800))
                    }
                    cautionWarnings.forEach { w ->
                        Text(w.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 24.dp, top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveEventCard(event: TimelineEvent, sessionStart: Long) {
    val elapsed = event.timestamp - sessionStart
    val mins = elapsed / 60000
    val secs = (elapsed % 60000) / 1000
    val timeStr = "+${mins}m${secs}s"

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = when (event.eventType) {
                    TimelineEventType.SIDE_EFFECT, TimelineEventType.EMERGENCY -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                    TimelineEventType.OBSERVATION, TimelineEventType.NOTE -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when (event.eventType) {
                        TimelineEventType.ONSET -> Icons.Default.ArrowForward
                        TimelineEventType.COMEUP -> Icons.Default.TrendingUp
                        TimelineEventType.PEAK -> Icons.Default.Star
                        TimelineEventType.OFFSET -> Icons.Default.TrendingDown
                        TimelineEventType.AFTERGLOW -> Icons.Default.NightsStay
                        TimelineEventType.END -> Icons.Default.Stop
                        TimelineEventType.OBSERVATION -> Icons.Default.Visibility
                        TimelineEventType.SAFETY_CHECK -> Icons.Default.CheckCircle
                        TimelineEventType.SIDE_EFFECT -> Icons.Default.Warning
                        TimelineEventType.EMERGENCY -> Icons.Default.Error
                        TimelineEventType.NOTE -> Icons.Default.Notes
                        TimelineEventType.PLATEAU -> Icons.Default.HorizontalRule
                    }
                    Icon(icon, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(event.label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text(timeStr, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!event.body.isNullOrBlank()) {
                    Text(event.body, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (event.intensity != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Intensity:", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("${event.intensity.toInt()}/10",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveDoseCard(dose: Dose, substance: Substance?, sessionStart: Long) {
    val elapsed = dose.timestamp - sessionStart
    val mins = elapsed / 60000
    val timeStr = "+${mins}m"

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Science, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(substance?.name ?: dose.substanceId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text("${dose.amount} ${dose.unit} - ${dose.routeOfAdministration}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(timeStr, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickDoseDialog(
    substances: List<Substance>,
    session: Session,
    repo: JournalRepository,
    onDismiss: () -> Unit,
) {
    val now = currentTimeMillis()
    var selectedSubstanceId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mg") }
    var route by remember { mutableStateOf("Oral") }
    var note by remember { mutableStateOf("") }

    val roaOptions = listOf("Oral", "Sublingual", "Insufflated", "Inhaled",
        "Vaporized", "Intranasal", "Intramuscular", "Intravenous", "Rectal")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Dose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Substance selector
                if (substances.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = substances.find { it.id == selectedSubstanceId }?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Substance") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            substances.forEach { sub ->
                                DropdownMenuItem(
                                    text = { Text(sub.name) },
                                    onClick = {
                                        selectedSubstanceId = sub.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text("No substances in database. Add one first.",
                        color = MaterialTheme.colorScheme.error)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it },
                        label = { Text("Amount") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = unit, onValueChange = { unit = it },
                        label = { Text("Unit") }, singleLine = true,
                        modifier = Modifier.width(80.dp))
                }

                // Route selector
                var routeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = routeExpanded,
                    onExpandedChange = { routeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = route,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Route") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(routeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = routeExpanded,
                        onDismissRequest = { routeExpanded = false }
                    ) {
                        roaOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = { route = opt; routeExpanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("Note (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = {
                    if (selectedSubstanceId.isNotBlank() && amount.isNotBlank()) {
                        val dose = Dose(
                            id = "dose:live:${now}_${session.id}",
                            sessionId = session.id,
                            substanceId = selectedSubstanceId,
                            routeOfAdministration = route,
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            unit = unit,
                            timestamp = now,
                            createdAt = now, updatedAt = now,
                            deviceOrigin = "desktop",
                        )
                        repo.upsertDose(dose)

                        // Also add a timeline event for the dose
                        val sub = substances.find { it.id == selectedSubstanceId }
                        repo.upsertTimelineEvent(TimelineEvent(
                            id = "event:dose:${now}_${session.id}",
                            sessionId = session.id,
                            timestamp = now,
                            eventType = TimelineEventType.NOTE,
                            label = "Dose: ${sub?.name ?: selectedSubstanceId}",
                            body = "${amount} ${unit} $route".takeIf { it.isNotBlank() },
                            createdAt = now, updatedAt = now,
                            deviceOrigin = "desktop",
                        ))

                        onDismiss()
                    }
                },
                enabled = selectedSubstanceId.isNotBlank() && amount.isNotBlank(),
            ) { Text("Log") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun QuickMoodDialog(
    session: Session,
    repo: JournalRepository,
    onDismiss: () -> Unit,
) {
    val now = currentTimeMillis()
    var mood by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf(5f) }
    var note by remember { mutableStateOf("") }

    val moodOptions = listOf("Calm", "Euphoric", "Anxious", "Focused",
        "Tired", "Awestruck", "Introspective", "Happy", "Overwhelmed", "Peaceful")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How are you feeling?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Mood quick-select chips
                Text("Mood", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    moodOptions.take(5).forEach { opt ->
                        FilterChip(
                            selected = mood == opt,
                            onClick = { mood = if (mood == opt) "" else opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    moodOptions.drop(5).forEach { opt ->
                        FilterChip(
                            selected = mood == opt,
                            onClick = { mood = if (mood == opt) "" else opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                // Intensity slider
                Text("Intensity: ${intensity.toInt()}/10",
                    style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = intensity,
                    onValueChange = { intensity = it },
                    valueRange = 1f..10f,
                    steps = 8,
                )

                // Note
                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = {
                    val label = if (mood.isNotBlank()) mood else "Check-in"
                    repo.upsertTimelineEvent(TimelineEvent(
                        id = "event:mood:${now}_${session.id}",
                        sessionId = session.id,
                        timestamp = now,
                        eventType = TimelineEventType.OBSERVATION,
                        label = label,
                        body = note.ifBlank { null },
                        intensity = intensity,
                        createdAt = now, updatedAt = now,
                        deviceOrigin = "desktop",
                    ))
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CrisisResourcesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Emergency, null, tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp))
                Text("Get Help Now")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("If you are experiencing a medical emergency or need immediate support:",
                    style = MaterialTheme.typography.bodyMedium)

                CrisisResourceCard(
                    "Emergency Services",
                    "112 (EU) / 911 (US)",
                    "Call immediately if someone is in physical danger."
                )
                CrisisResourceCard(
                    "Poison Control",
                    "1-800-222-1222 (US)",
                    "For overdoses, bad reactions, and interactions."
                )
                CrisisResourceCard(
                    "Fireside Project",
                    "62-FIRESIDE (623-473-7433)",
                    "Free psychedelic peer support line (US), 11am-11pm PT."
                )
                CrisisResourceCard(
                    "Suicide & Crisis Lifeline (US)",
                    "988 (call or text)",
                    "24/7 support for suicidal thoughts, self-harm, or crisis."
                )
            }
        },
        confirmButton = {
            AppTextButton(onClick = onDismiss) { Text("I understand") }
        }
    )
}

@Composable
private fun CrisisResourceCard(label: String, contact: String, detail: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
            Text(contact, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
