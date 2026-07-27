package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.InteractionCheckResult
import app.journal.data.InteractionChecker
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.InteractionWarnings
import app.journal.ui.components.*
import app.journal.ui.components.TagChip
import app.journal.util.currentTimeMillis
import androidx.compose.ui.text.font.FontWeight
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorScreen(
    repo: JournalRepository = JournalRepository.instance,
    sessionToEdit: Session? = null,
    onBack: () -> Unit
) {
    val substances by repo.substances.collectAsState()
    val useShulgin by repo.useShulginRating.collectAsState()
    val isEditing = sessionToEdit != null

    // Snapshot interactions once — doesn't cause recomposition on every
    // change at runtime. The InteractionChecker caches its index by
    // content hash, so rebuild is skipped even on sessionDoses change.
    val allInteractions = remember { repo.interactions.value }

    // Form state
    var title by remember { mutableStateOf(sessionToEdit?.title ?: "") }
    var startTime by remember {
        mutableStateOf(sessionToEdit?.startTime ?: currentTimeMillis())
    }
    var endTime by remember { mutableStateOf(sessionToEdit?.endTime) }
    var set by remember { mutableStateOf(sessionToEdit?.set ?: "") }
    var setting by remember { mutableStateOf(sessionToEdit?.setting ?: "") }
    var intention by remember { mutableStateOf(sessionToEdit?.intention ?: "") }
    var outcome by remember { mutableStateOf(sessionToEdit?.outcome ?: "") }
    var notes by remember { mutableStateOf(sessionToEdit?.notes ?: "") }
    var rating by remember { mutableStateOf(sessionToEdit?.rating?.toString() ?: "") }
    var shulginRating by remember {
        mutableStateOf(sessionToEdit?.shulginRating ?: "")
    }

    // Dose editing
    var showDoseDialog by remember { mutableStateOf(false) }
    var editingDose by remember { mutableStateOf<Dose?>(null) }
    var sessionDoses by remember {
        mutableStateOf(
            if (sessionToEdit != null) repo.dosesForSession(sessionToEdit.id) else emptyList()
        )
    }

    // Timeline events
    var sessionEvents by remember {
        mutableStateOf(
            if (sessionToEdit != null) repo.eventsForSession(sessionToEdit.id)
            else emptyList()
        )
    }

    // End time validation
    val endTimeValue = endTime
    val endTimeError = endTimeValue != null && endTimeValue <= startTime

    // Discard confirmation
    var showDiscardDialog by remember { mutableStateOf(false) }
    val hasUnsavedChanges by remember {
        derivedStateOf {
            val s = sessionToEdit
            val originalDoseIds = s?.let { repo.dosesForSession(it.id).map { d -> d.id }.toSet() }
                ?: emptySet()
            val currentDoseIds = sessionDoses.map { it.id }.toSet()
            title != (s?.title ?: "") ||
            set != (s?.set ?: "") ||
            setting != (s?.setting ?: "") ||
            intention != (s?.intention ?: "") ||
            outcome != (s?.outcome ?: "") ||
            notes != (s?.notes ?: "") ||
            rating != (s?.rating?.toString() ?: "") ||
            shulginRating != (s?.shulginRating ?: "") ||
            currentDoseIds != originalDoseIds
        }
    }

    fun handleBack() {
        if (hasUnsavedChanges) showDiscardDialog = true else onBack()
    }

    fun saveSession() {
        val now = currentTimeMillis()
        val sessionId = sessionToEdit?.id ?: "session:${now}"
        val session = Session(
            id = sessionId,
            title = title.ifBlank { "Untitled Session" },
            startTime = startTime,
            endTime = if (endTimeValue != null && endTimeValue > 1000L && endTimeValue != startTime) endTimeValue else null,
            set = set.ifBlank { null },
            setting = setting.ifBlank { null },
            intention = intention.ifBlank { null },
            outcome = outcome.ifBlank { null },
            notes = notes.ifBlank { null },
            rating = if (useShulgin) {
                shulginRating.let { s -> ShulginRating.entries.find { it.name == s }?.numericValue }
            } else rating.toIntOrNull(),
            shulginRating = if (useShulgin) shulginRating.ifBlank { null } else null,
            checkins = sessionToEdit?.checkins ?: emptyList(),
            createdAt = sessionToEdit?.createdAt ?: now,
            updatedAt = now,
            deviceOrigin = sessionToEdit?.deviceOrigin ?: "desktop"
        )
        repo.upsertSession(session)

        // Auto-export to Obsidian if enabled
        if (repo.obsidianAutoExport.value && repo.obsidianVaultPath.value.isNotBlank()) {
            try {
                val config = app.journal.export.obsidian.ObsidianExportConfig(
                    vaultPath = repo.obsidianVaultPath.value,
                    subfolder = repo.obsidianSubfolder.value
                )
                app.journal.export.obsidian.ObsidianExportManager.exportSession(
                    repo, sessionId, config
                )
            } catch (_: Exception) {
                // Silent — auto-export failures are non-critical
            }
        }

        val existingIds = if (sessionToEdit != null)
            repo.dosesForSession(sessionToEdit.id).map { it.id }.toSet() else emptySet()
        val keptIds = mutableSetOf<String>()
        sessionDoses.forEach { dose ->
            val d = if (dose.sessionId != sessionId) dose.copy(sessionId = sessionId) else dose
            repo.upsertDose(d)
            keptIds.add(d.id)
        }
        (existingIds - keptIds).forEach { repo.deleteDose(it) }

        onBack()
    }

    // Compute interaction warnings from current doses
    val interactionCheckResult = remember(sessionDoses) {
        if (sessionDoses.size >= 2) {
            val substanceIds = sessionDoses.map { it.substanceId }.distinct()
            InteractionChecker.checkPairwise(substanceIds, allInteractions)
        } else {
            InteractionCheckResult()
        }
    }

    // Discard confirmation dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to go back?") },
            confirmButton = {
                AppTextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            }
        )
    }

    // Dose editor dialog
    if (showDoseDialog) {
        DoseEditDialog(
            substances = substances,
            sessionStartTime = startTime,
            initialDose = editingDose,
            onDismiss = { showDoseDialog = false; editingDose = null },
            customUnits = repo.customUnits.value,
            onSave = { dose ->
                sessionDoses = if (sessionDoses.any { it.id == dose.id })
                    sessionDoses.map { if (it.id == dose.id) dose else it }
                else sessionDoses + dose
                showDoseDialog = false
                editingDose = null
            }
        )
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete session?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                AppTextButton(onClick = {
                    sessionToEdit?.let { repo.deleteSession(it.id) }
                    showDeleteConfirm = false
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Main layout
    ScreenScaffold(
        title = if (isEditing) "Edit Session" else "New Session",
        onBack = ::handleBack,
        actions = {
            if (isEditing) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            AppTextButton(onClick = ::saveSession) { Text("Save") }
        }
    ) {
        // Title
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("Session title...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Start / End time
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimeField(
                        label = "Start",
                        epochMs = startTime,
                        onChanged = { startTime = it },
                        modifier = Modifier.weight(1f)
                    )
                    TimeField(
                        label = "End",
                        epochMs = endTime,
                        onChanged = { endTime = it },
                        modifier = Modifier.weight(1f),
                        clearable = endTime != null
                    )
                }
            }
            if (endTimeError) {
                Text("End time must be after start time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
            // Duration display
            val durationMs = if (endTimeValue != null && !endTimeError) endTimeValue - startTime else null
            if (durationMs != null && durationMs > 0) {
                val hours = durationMs / 3600000
                val minutes = (durationMs % 3600000) / 60000
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                ) {
                    Icon(Icons.Default.Schedule, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                    Text("Duration: ${hours}h ${minutes}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Rating
        item {
            Text("Rating", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (useShulgin) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShulginRating.entries.forEach { s ->
                        FilterChip(
                            selected = shulginRating == s.name,
                            onClick = {
                                shulginRating = if (shulginRating == s.name) "" else s.name
                            },
                            label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (1..10).forEach { n ->
                        FilterChip(
                            selected = rating == n.toString(),
                            onClick = {
                                rating = if (rating == n.toString()) "" else n.toString()
                            },
                            label = { Text(n.toString(), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }
        }

        // Set & Setting
        item {
            OutlinedTextField(
                value = set,
                onValueChange = { set = it },
                label = { Text("Set (mindset)") },
                placeholder = { Text("Your mental state before the session...") },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = setting,
                onValueChange = { setting = it },
                label = { Text("Setting (environment)") },
                placeholder = { Text("Location, atmosphere, company...") },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Intention
        item {
            OutlinedTextField(
                value = intention,
                onValueChange = { intention = it },
                label = { Text("Intention") },
                placeholder = { Text("Why are you having this session?") },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Outcome
        item {
            OutlinedTextField(
                value = outcome,
                onValueChange = { outcome = it },
                label = { Text("Outcome") },
                placeholder = { Text("What happened? Insights, reflections...") },
                minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Notes
        item {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                placeholder = { Text("Freeform notes, observations...") },
                minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Doses section header
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Substances & Doses",
                    style = MaterialTheme.typography.titleMedium)
                AppTonalButton(onClick = {
                    editingDose = null
                    showDoseDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Dose")
                }
            }
        }

        // Dose list — clickable to edit, delete button inline
        items(sessionDoses, key = { it.id }) { dose ->
            val substance = repo.getSubstance(dose.substanceId)
            val roaColor = routeColor(dose.routeOfAdministration)
            val doseLocal = remember(dose.timestamp) {
                try {
                    Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
                } catch (_: Exception) { null }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    editingDose = dose
                    showDoseDialog = true
                },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Route color indicator
                    Surface(
                        modifier = Modifier.size(4.dp, 40.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = roaColor
                    ) {}
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = substance?.name ?: stripPrefix(dose.substanceId),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = roaColor
                            ) {}
                            Text(dose.routeOfAdministration,
                                style = MaterialTheme.typography.labelSmall,
                                color = roaColor)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = buildString {
                                    append("${dose.amount} ${dose.unit}")
                                    if (dose.redosing) append(" · redose")
                                    if (dose.isDoseEstimate) append(" · est. ±${dose.estimatedDoseStandardDeviation}")
                                    if (dose.stomachFullness != null) append(" · ${dose.stomachFullness.label}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (doseLocal != null) {
                            Text(
                                text = "${doseLocal.year}-${(doseLocal.month.ordinal + 1).toString().padStart(2,'0')}-${doseLocal.day.toString().padStart(2,'0')} " +
                                       "${doseLocal.hour.toString().padStart(2,'0')}:${doseLocal.minute.toString().padStart(2,'0')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    IconButton(onClick = {
                        sessionDoses = sessionDoses.filter { it.id != dose.id }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Interaction warnings for combined substances
        if (interactionCheckResult.hasIssues || interactionCheckResult.uncertain.isNotEmpty()) {
            item {
                InteractionWarnings(
                    result = interactionCheckResult,
                    substanceNameLookup = { id -> repo.getSubstance(id)?.name ?: stripPrefix(id) },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ── Timeline Events section ──
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Timeline Events",
                    style = MaterialTheme.typography.titleMedium)
                AppTonalButton(onClick = {
                    val now = currentTimeMillis()
                    val newEvent = TimelineEvent(
                        id = "evt:${now}",
                        sessionId = sessionToEdit?.id ?: "session:${now}",
                        eventType = TimelineEventType.OBSERVATION,
                        label = "Check-in",
                        timestamp = now,
                        body = "Quick event",
                        createdAt = now, updatedAt = now,
                        deviceOrigin = "desktop"
                    )
                    // Persist immediately so it appears with a real id
                    repo.upsertTimelineEvent(newEvent)
                    sessionEvents = sessionEvents + newEvent
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Event")
                }
            }
        }

        // Inline-editable event cards
        items(sessionEvents.sortedBy { it.timestamp }, key = { it.id }) { event ->
            EditorInlineEventCard(
                event = event,
                repo = repo,
                onDelete = {
                    repo.deleteTimelineEvent(event.id)
                    sessionEvents = sessionEvents.filter { it.id != event.id }
                }
            )
        }
    }
}

@Composable
private fun EditorInlineEventCard(
    event: TimelineEvent,
    repo: JournalRepository,
    onDelete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(event.label) }
    var editBody by remember { mutableStateOf(event.body ?: "") }
    var editIntensity by remember { mutableFloatStateOf(event.intensity ?: 5f) }
    var editType by remember { mutableStateOf(event.eventType) }
    var useIntensity by remember { mutableStateOf(event.intensity != null) }

    if (editing) {
        // INLINE EDIT MODE
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Event type chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    val quickTypes = listOf(
                        TimelineEventType.OBSERVATION to "Obs",
                        TimelineEventType.NOTE to "Note",
                        TimelineEventType.ONSET to "On",
                        TimelineEventType.COMEUP to "Up",
                        TimelineEventType.PEAK to "Peak",
                        TimelineEventType.OFFSET to "Off",
                        TimelineEventType.AFTERGLOW to "Glow",
                        TimelineEventType.SIDE_EFFECT to "SE",
                        TimelineEventType.EMERGENCY to "!"
                    )
                    quickTypes.forEach { (type, lbl) ->
                        FilterChip(
                            selected = editType == type,
                            onClick = { editType = type },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
                OutlinedTextField(value = editLabel, onValueChange = { editLabel = it },
                    label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editBody, onValueChange = { editBody = it },
                    label = { Text("Notes") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = useIntensity, onClick = { useIntensity = !useIntensity },
                        label = { Text("Intensity", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(26.dp))
                    if (useIntensity) {
                        Text("${editIntensity.toInt()}/10", style = MaterialTheme.typography.labelSmall)
                        Slider(value = editIntensity, onValueChange = { editIntensity = it },
                            valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(onClick = { editing = false }) { Text("Cancel") }
                    AppTextButton(onClick = {
                        repo.upsertTimelineEvent(event.copy(
                            eventType = editType,
                            label = editLabel.ifBlank { event.label },
                            body = editBody.ifBlank { null },
                            intensity = if (useIntensity) editIntensity else null,
                            updatedAt = currentTimeMillis()
                        ))
                        editing = false
                    }) { Text("Save") }
                }
            }
        }
    } else {
        // VIEW MODE
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
        val accent = when (event.eventType) {
            TimelineEventType.EMERGENCY, TimelineEventType.SIDE_EFFECT -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.label, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        if (!event.body.isNullOrBlank()) {
                            Text(event.body, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (event.intensity != null) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Intensity:", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("${event.intensity.toInt()}/10",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun stripPrefix(id: String): String {
    if (id.startsWith("pwiki:")) {
        return id.removePrefix("pwiki:").replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    return id
}
