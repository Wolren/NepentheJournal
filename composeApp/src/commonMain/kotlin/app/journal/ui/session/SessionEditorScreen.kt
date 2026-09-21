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
import app.journal.data.IJournalRepository
import app.journal.log.Log
import app.journal.model.*
import app.journal.ui.components.InteractionWarnings
import app.journal.ui.components.*
import app.journal.ui.components.TagChip
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin
import kotlin.random.Random
import androidx.compose.ui.text.font.FontWeight
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorScreen(
    repo: IJournalRepository = JournalRepository.instance,
    sessionToEdit: Session? = null,
    onBack: () -> Unit
) {
    val substances by repo.substances.collectAsState()
    val substanceNameById = remember(substances) { substances.associate { it.id to it.name } }
    val ratingMode by repo.ratingScaleMode.collectAsState()
    val useShulgin = ratingMode == RatingScaleMode.SHULGIN
    val isEditing = sessionToEdit != null

    // Reactive interactions: refresh warnings when the store changes.
    val allInteractions by repo.interactions.collectAsState()

    // Form state
    var title by remember { mutableStateOf(sessionToEdit?.title ?: "") }
    var startTime by remember {
        mutableStateOf(sessionToEdit?.startTime ?: currentTimeMillis())
    }
    var endTime by remember { mutableStateOf(sessionToEdit?.endTime) }
    var consumerName by remember { mutableStateOf(sessionToEdit?.consumerName ?: "") }
    var personId by remember { mutableStateOf(sessionToEdit?.personId) }
    val persons by repo.persons.collectAsState()
    val customUnits by repo.customUnits.collectAsState()
    var set by remember { mutableStateOf(sessionToEdit?.set ?: "") }
    var setting by remember { mutableStateOf(sessionToEdit?.setting ?: "") }
    var intention by remember { mutableStateOf(sessionToEdit?.intention ?: "") }
    var outcome by remember { mutableStateOf(sessionToEdit?.outcome ?: "") }
    var notes by remember { mutableStateOf(sessionToEdit?.notes ?: "") }
    var tags by remember { mutableStateOf(sessionToEdit?.tags?.joinToString(", ") ?: "") }
    var isFavorite by remember { mutableStateOf(sessionToEdit?.isFavorite ?: false) }
    var rating by remember { mutableStateOf(sessionToEdit?.rating?.toString() ?: "") }
    var shulginRating by remember {
        mutableStateOf(sessionToEdit?.shulginRating ?: "")
    }

    // SessionProfile (demographics) state
    val initialProfile = sessionToEdit?.profile
    var profileExpanded by remember { mutableStateOf(false) }
    var profileAge by remember { mutableStateOf(initialProfile?.age?.toString() ?: "") }
    var profileGender by remember { mutableStateOf(initialProfile?.gender ?: "") }
    var profileHeightCm by remember { mutableStateOf(initialProfile?.heightCm?.toString() ?: "") }
    var profileWeightKg by remember { mutableStateOf(initialProfile?.weightKg?.toString() ?: "") }

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

    // Stable provisional id for events of a not-yet-saved session. Events are
    // persisted immediately when added, so they need an id that can be
    // re-parented to the real session id on save (and purged on discard).
    val draftSessionId = remember { "session:draft:${currentTimeMillis()}_${Random.nextInt(0, 0x10000).toString(16)}" }

    // End time validation
    val endTimeValue = endTime

    // Discard confirmation
    var showDiscardDialog by remember { mutableStateOf(false) }
    val originalDoseIds = remember(sessionToEdit?.id) {
        sessionToEdit?.let { repo.dosesForSession(it.id).map { d -> d.id }.toSet() } ?: emptySet()
    }
    val hasUnsavedChanges by remember {
        derivedStateOf {
            val s = sessionToEdit
            val currentDoseIds = sessionDoses.map { it.id }.toSet()
            val originalProfile = s?.profile
            title != (s?.title ?: "") ||
            consumerName != (s?.consumerName ?: "") ||
            personId != s?.personId ||
            set != (s?.set ?: "") ||
            setting != (s?.setting ?: "") ||
            intention != (s?.intention ?: "") ||
            outcome != (s?.outcome ?: "") ||
            notes != (s?.notes ?: "") ||
            tags != (s?.tags?.joinToString(", ") ?: "") ||
            rating != (s?.rating?.toString() ?: "") ||
            shulginRating != (s?.shulginRating ?: "") ||
            profileAge != (originalProfile?.age?.toString() ?: "") ||
            profileGender != (originalProfile?.gender ?: "") ||
            profileHeightCm != (originalProfile?.heightCm?.toString() ?: "") ||
            profileWeightKg != (originalProfile?.weightKg?.toString() ?: "") ||
            currentDoseIds != originalDoseIds
        }
    }

    fun exitEditor() {
        // New-session draft: purge any events still bound to the draft id.
        // Events are persisted on add, so a discarded draft would otherwise
        // leave orphaned rows pointing at a session that never existed.
        if (sessionToEdit == null) {
            repo.eventsForSession(draftSessionId).forEach { repo.deleteTimelineEvent(it.id) }
        }
        onBack()
    }

    fun handleBack() {
        if (hasUnsavedChanges) showDiscardDialog = true else exitEditor()
    }

    fun buildProfile(): SessionProfile? {
        val age = profileAge.toIntOrNull()
        val height = profileHeightCm.toIntOrNull()
        val weight = profileWeightKg.toIntOrNull()
        val gender = profileGender.ifBlank { null }
        if (age == null && gender == null && height == null && weight == null) return null
        return SessionProfile(
            age = age,
            gender = gender,
            heightCm = height,
            weightKg = weight
        )
    }

    fun saveSession() {
        val now = currentTimeMillis()
        val sessionId = sessionToEdit?.id ?: "session:${now}_${Random.nextInt(0, 0x10000).toString(16)}"
        val session = Session(
            id = sessionId,
            title = title.ifBlank { "Untitled Session" },
            startTime = startTime,
            endTime = if (endTimeValue != null && endTimeValue > 1000L && endTimeValue != startTime) endTimeValue else null,
            consumerName = consumerName.ifBlank { null },
            personId = personId,
            set = set.ifBlank { null },
            setting = setting.ifBlank { null },
            intention = intention.ifBlank { null },
            outcome = outcome.ifBlank { null },
            notes = notes.ifBlank { null },
            tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            rating = if (useShulgin) {
                shulginRating.let { s -> ShulginRating.entries.find { it.name == s }?.numericValue }
            } else rating.toIntOrNull()?.coerceIn(1, 10),
            shulginRating = if (useShulgin) shulginRating.ifBlank { null } else null,
            checkins = sessionToEdit?.checkins ?: emptyList(),
            isFavorite = isFavorite,
            isArchived = sessionToEdit?.isArchived ?: false,
            profile = buildProfile(),
            createdAt = sessionToEdit?.createdAt ?: now,
            updatedAt = now,
            deviceOrigin = sessionToEdit?.deviceOrigin ?: platformDeviceOrigin()
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
            } catch (e: Exception) {
                Log.withTag("SessionEdit").w(e) { "Auto-export to Obsidian failed" }
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

        // Re-parent timeline events from the draft id to the saved session id.
        // Events added in the editor were persisted immediately under the
        // draft id; without this they would be invisible in the timeline.
        sessionEvents.forEach { event ->
            val e = if (event.sessionId != sessionId) event.copy(sessionId = sessionId) else event
            repo.upsertTimelineEvent(e)
        }

        onBack()
    }

    // Compute interaction warnings from current doses; keyed on both inputs so
    // store updates also refresh the warnings.
    val interactionCheckResult = remember(sessionDoses, allInteractions) {
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
                AppTextButton(onClick = { showDiscardDialog = false; exitEditor() }) {
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
            customUnits = customUnits,
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

    // Delete confirmation dialog (delete lives here, inside the open
    // session, never on the list card)
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
            IconButton(onClick = { isFavorite = !isFavorite }) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Toggle favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isEditing) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            AppButton(onClick = ::saveSession) { Text("Save") }
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

        // Individual assigned to this trip (owns the demographics at export time)
        item {
            PersonPickerSection(
                persons = persons,
                selectedPersonId = personId,
                onSelect = { personId = it },
                onCreatePerson = { person ->
                    repo.upsertPerson(person)
                    personId = person.id
                }
            )
        }

        // Consumer name fallback, only without an assigned individual
        if (personId == null) {
            item {
                OutlinedTextField(
                    value = consumerName,
                    onValueChange = { consumerName = it },
                    label = { Text("Consumer name") },
                    placeholder = { Text("Me") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Start / End time
        item {
            SessionTimeSection(
                startTime = startTime,
                endTime = endTime,
                onStartTimeChange = { startTime = it },
                onEndTimeChange = { endTime = it }
            )
        }

        // Rating (hidden entirely when the scale is off in settings)
        if (ratingMode != RatingScaleMode.OFF) {
            item {
                SessionRatingSection(
                    useShulgin = useShulgin,
                    shulginRating = shulginRating,
                    onShulginRatingChange = { shulginRating = it },
                    rating = rating,
                    onRatingChange = { rating = it }
                )
            }
        }

        // Set & Setting, Intention, Outcome, Notes
        item {
            SessionTextFieldsSection(
                set = set, onSetChange = { set = it },
                setting = setting, onSettingChange = { setting = it },
                intention = intention, onIntentionChange = { intention = it },
                outcome = outcome, onOutcomeChange = { outcome = it },
                notes = notes, onNotesChange = { notes = it },
                tags = tags, onTagsChange = { tags = it }
            )
        }

        // Legacy per-session demographics, only without an assigned individual.
        // With an individual, demographics live on the individual (Settings, Individuals).
        if (personId == null) {
        item {
            SessionDemographicsSection(
                expanded = profileExpanded,
                onToggle = { profileExpanded = !profileExpanded },
                age = profileAge, onAgeChange = { profileAge = it },
                gender = profileGender, onGenderChange = { profileGender = it },
                heightCm = profileHeightCm, onHeightCmChange = { profileHeightCm = it },
                weightKg = profileWeightKg, onWeightKgChange = { profileWeightKg = it }
            )
        }
        }

        // Doses section
        item {
            SessionDoseSection(
                sessionDoses = sessionDoses,
                interactionCheckResult = interactionCheckResult,
                substanceNameLookup = { id -> substanceNameById[id] ?: stripPrefix(id) },
                onAddDose = {
                    editingDose = null
                    showDoseDialog = true
                },
                onEditDose = { dose ->
                    editingDose = dose
                    showDoseDialog = true
                },
                onDeleteDose = { doseId ->
                    sessionDoses = sessionDoses.filter { it.id != doseId }
                }
            )
        }

        // Timeline Events section
        item {
            SessionEventsSection(
                sessionEvents = sessionEvents,
                sessionId = sessionToEdit?.id ?: draftSessionId,
                repo = repo,
                onEventAdded = { event ->
                    sessionEvents = sessionEvents + event
                },
                onEventDeleted = { eventId ->
                    // Events are persisted on add, so deletion must hit the
                    // repo too, otherwise the event reappears on reopen.
                    repo.deleteTimelineEvent(eventId)
                    sessionEvents = sessionEvents.filter { it.id != eventId }
                }
            )
        }
    }
}

private fun stripPrefix(id: String): String {
    if (id.startsWith("pwiki:")) {
        return id.removePrefix("pwiki:").replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    return id
}
