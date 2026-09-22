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
import app.journal.model.rules.InteractionCheckResult
import app.journal.data.IJournalRepository
import app.journal.ui.LocalJournalRepository
import app.journal.model.*
import app.journal.ui.components.InteractionWarnings
import app.journal.ui.components.*
import kotlin.random.Random
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorScreen(
    repo: IJournalRepository = LocalJournalRepository.current,
    sessionToEdit: Session? = null,
    onBack: () -> Unit
) {
    // Form state, validation, session construction and the save pipeline all
    // live in the view model; this composable only renders and forwards intent.
    val viewModel = remember(repo, sessionToEdit?.id) {
        SessionEditorViewModel.create(repo, sessionToEdit)
    }

    val substances by repo.substances.collectAsState()
    val substanceNameById = remember(substances) { substances.associate { it.id to it.name } }
    val ratingMode by repo.ratingScaleMode.collectAsState()
    val useShulgin = ratingMode == RatingScaleMode.SHULGIN
    val isEditing = sessionToEdit != null
    val persons by repo.persons.collectAsState()
    val customUnits by repo.customUnits.collectAsState()

    // Form state
    val title by viewModel.title.collectAsState()
    val startTime by viewModel.startTime.collectAsState()
    val endTime by viewModel.endTime.collectAsState()
    val consumerName by viewModel.consumerName.collectAsState()
    val personId by viewModel.personId.collectAsState()
    val set by viewModel.set.collectAsState()
    val setting by viewModel.setting.collectAsState()
    val intention by viewModel.intention.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val rating by viewModel.rating.collectAsState()
    val shulginRating by viewModel.shulginRating.collectAsState()

    // SessionProfile (demographics) state
    var profileExpanded by remember { mutableStateOf(false) }
    val profileAge by viewModel.profileAge.collectAsState()
    val profileGender by viewModel.profileGender.collectAsState()
    val profileHeightCm by viewModel.profileHeightCm.collectAsState()
    val profileWeightKg by viewModel.profileWeightKg.collectAsState()

    // Dose and timeline-event drafts
    var showDoseDialog by remember { mutableStateOf(false) }
    var editingDose by remember { mutableStateOf<Dose?>(null) }
    val sessionDoses by viewModel.sessionDoses.collectAsState()
    val sessionEvents by viewModel.sessionEvents.collectAsState()

    // Discard confirmation
    var showDiscardDialog by remember { mutableStateOf(false) }
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState(initial = false)

    // Interaction warnings keyed on doses + stored interactions (view model).
    val interactionCheckResult by viewModel.interactionCheckResult.collectAsState(
        initial = InteractionCheckResult()
    )

    fun exitEditor() {
        viewModel.discardDraft()
        onBack()
    }

    fun handleBack() {
        if (hasUnsavedChanges) showDiscardDialog = true else exitEditor()
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
                viewModel.upsertDose(dose)
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
                    viewModel.deleteSession()
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
            IconButton(onClick = { viewModel.isFavorite.value = !isFavorite }) {
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
            AppButton(onClick = {
                viewModel.save()
                onBack()
            }) { Text("Save") }
        }
    ) {
        // Title
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { viewModel.title.value = it },
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
                onSelect = { viewModel.personId.value = it },
                onCreatePerson = { person -> viewModel.createPerson(person) }
            )
        }

        // Consumer name fallback, only without an assigned individual
        if (personId == null) {
            item {
                OutlinedTextField(
                    value = consumerName,
                    onValueChange = { viewModel.consumerName.value = it },
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
                onStartTimeChange = { viewModel.startTime.value = it },
                onEndTimeChange = { viewModel.endTime.value = it }
            )
        }

        // Rating (hidden entirely when the scale is off in settings)
        if (ratingMode != RatingScaleMode.OFF) {
            item {
                SessionRatingSection(
                    useShulgin = useShulgin,
                    shulginRating = shulginRating,
                    onShulginRatingChange = { viewModel.shulginRating.value = it },
                    rating = rating,
                    onRatingChange = { viewModel.rating.value = it }
                )
            }
        }

        // Set & Setting, Intention, Outcome, Notes
        item {
            SessionTextFieldsSection(
                set = set, onSetChange = { viewModel.set.value = it },
                setting = setting, onSettingChange = { viewModel.setting.value = it },
                intention = intention, onIntentionChange = { viewModel.intention.value = it },
                outcome = outcome, onOutcomeChange = { viewModel.outcome.value = it },
                notes = notes, onNotesChange = { viewModel.notes.value = it },
                tags = tags, onTagsChange = { viewModel.tags.value = it }
            )
        }

        // Legacy per-session demographics, only without an assigned individual.
        // With an individual, demographics live on the individual (Settings, Individuals).
        if (personId == null) {
        item {
            SessionDemographicsSection(
                expanded = profileExpanded,
                onToggle = { profileExpanded = !profileExpanded },
                age = profileAge, onAgeChange = { viewModel.profileAge.value = it },
                gender = profileGender, onGenderChange = { viewModel.profileGender.value = it },
                heightCm = profileHeightCm, onHeightCmChange = { viewModel.profileHeightCm.value = it },
                weightKg = profileWeightKg, onWeightKgChange = { viewModel.profileWeightKg.value = it }
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
                    viewModel.deleteDose(doseId)
                }
            )
        }

        // Timeline Events section
        item {
            SessionEventsSection(
                sessionEvents = sessionEvents,
                sessionId = sessionToEdit?.id ?: viewModel.draftSessionId,
                repo = repo,
                onEventAdded = { event ->
                    viewModel.addEvent(event)
                },
                onEventDeleted = { eventId ->
                    viewModel.deleteEvent(eventId)
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
