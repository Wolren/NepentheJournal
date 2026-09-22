package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.log.Log
import app.journal.model.Dose
import app.journal.model.Person
import app.journal.model.RatingScaleMode
import app.journal.model.Session
import app.journal.model.SessionProfile
import app.journal.model.ShulginRating
import app.journal.model.TimelineEvent
import app.journal.model.rules.InteractionCheckResult
import app.journal.model.rules.InteractionChecker
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.random.Random

/**
 * Form state, validation, session construction and the save pipeline for
 * [SessionEditorScreen].
 *
 * Follows the [SessionListViewModel] pattern: the constructor takes the
 * [IJournalRepository] (plus the session being edited), raw form fields are
 * [MutableStateFlow]s the screen writes straight into, and every derived
 * value stays a cold [Flow] so nothing is eagerly collected or stateIn-ed.
 *
 * The screen keeps only UI state (dialog visibility, section expansion) and
 * forwards user intent here, so no construction, validation, repository
 * write or Obsidian auto-export logic lives in a composable.
 */
class SessionEditorViewModel(
    private val repo: IJournalRepository,
    private val sessionToEdit: Session?,
) {
    // ---- Form state (seeded from the session under edit, else defaults) ----
    val title = MutableStateFlow(sessionToEdit?.title ?: "")
    val startTime = MutableStateFlow(sessionToEdit?.startTime ?: currentTimeMillis())
    val endTime = MutableStateFlow(sessionToEdit?.endTime)
    val consumerName = MutableStateFlow(sessionToEdit?.consumerName ?: "")
    val personId = MutableStateFlow(sessionToEdit?.personId)
    val set = MutableStateFlow(sessionToEdit?.set ?: "")
    val setting = MutableStateFlow(sessionToEdit?.setting ?: "")
    val intention = MutableStateFlow(sessionToEdit?.intention ?: "")
    val outcome = MutableStateFlow(sessionToEdit?.outcome ?: "")
    val notes = MutableStateFlow(sessionToEdit?.notes ?: "")
    val tags = MutableStateFlow(sessionToEdit?.tags?.joinToString(", ") ?: "")
    val isFavorite = MutableStateFlow(sessionToEdit?.isFavorite ?: false)
    val rating = MutableStateFlow(sessionToEdit?.rating?.toString() ?: "")
    val shulginRating = MutableStateFlow(sessionToEdit?.shulginRating ?: "")

    // Legacy per-session demographics (SessionProfile) fields.
    private val initialProfile: SessionProfile? = sessionToEdit?.profile
    val profileAge = MutableStateFlow(initialProfile?.age?.toString() ?: "")
    val profileGender = MutableStateFlow(initialProfile?.gender ?: "")
    val profileHeightCm = MutableStateFlow(initialProfile?.heightCm?.toString() ?: "")
    val profileWeightKg = MutableStateFlow(initialProfile?.weightKg?.toString() ?: "")

    // ---- Doses and timeline events of the session being edited ----
    val sessionDoses = MutableStateFlow(
        if (sessionToEdit != null) repo.dosesForSession(sessionToEdit.id) else emptyList<Dose>()
    )
    val sessionEvents = MutableStateFlow(
        if (sessionToEdit != null) repo.eventsForSession(sessionToEdit.id) else emptyList<TimelineEvent>()
    )

    /**
     * Stable provisional id for events of a not-yet-saved session. Events are
     * persisted immediately when added, so they need an id that can be
     * re-parented to the real session id on save (and purged on discard).
     */
    val draftSessionId: String =
        "session:draft:${currentTimeMillis()}_${Random.nextInt(0, 0x10000).toString(16)}"

    /** Dose ids the session had when the editor opened, for change detection. */
    private val originalDoseIds: Set<String> =
        sessionToEdit?.let { repo.dosesForSession(it.id).map { d -> d.id }.toSet() } ?: emptySet()

    /** Every form field the dirty check watches; emitted on any edit. */
    private val watchedFields: List<Flow<Any?>> = listOf(
        title, consumerName, personId, set, setting, intention, outcome, notes,
        tags, rating, shulginRating, profileAge, profileGender, profileHeightCm,
        profileWeightKg, sessionDoses,
    )

    /** True when any field or dose differs from the session as opened. */
    val hasUnsavedChanges: Flow<Boolean> = combine(watchedFields) { dirtyNow() }

    private fun dirtyNow(): Boolean {
        val s = sessionToEdit
        val currentDoseIds = sessionDoses.value.map { it.id }.toSet()
        val originalProfile = s?.profile
        return title.value != (s?.title ?: "") ||
            consumerName.value != (s?.consumerName ?: "") ||
            personId.value != s?.personId ||
            set.value != (s?.set ?: "") ||
            setting.value != (s?.setting ?: "") ||
            intention.value != (s?.intention ?: "") ||
            outcome.value != (s?.outcome ?: "") ||
            notes.value != (s?.notes ?: "") ||
            tags.value != (s?.tags?.joinToString(", ") ?: "") ||
            rating.value != (s?.rating?.toString() ?: "") ||
            shulginRating.value != (s?.shulginRating ?: "") ||
            profileAge.value != (originalProfile?.age?.toString() ?: "") ||
            profileGender.value != (originalProfile?.gender ?: "") ||
            profileHeightCm.value != (originalProfile?.heightCm?.toString() ?: "") ||
            profileWeightKg.value != (originalProfile?.weightKg?.toString() ?: "") ||
            currentDoseIds != originalDoseIds
    }

    /**
     * Interaction warnings for the current dose list; re-emits when either
     * the doses or the stored interactions change.
     */
    val interactionCheckResult: Flow<InteractionCheckResult> =
        combine(sessionDoses, repo.interactions) { doses, allInteractions ->
            if (doses.size >= 2) {
                InteractionChecker.checkPairwise(
                    doses.map { it.substanceId }.distinct(), allInteractions
                )
            } else {
                InteractionCheckResult()
            }
        }

    /** Build the legacy demographics block, null when every field is blank. */
    private fun buildProfile(): SessionProfile? {
        val age = profileAge.value.toIntOrNull()
        val height = profileHeightCm.value.toIntOrNull()
        val weight = profileWeightKg.value.toIntOrNull()
        val gender = profileGender.value.ifBlank { null }
        if (age == null && gender == null && height == null && weight == null) return null
        return SessionProfile(
            age = age,
            gender = gender,
            heightCm = height,
            weightKg = weight
        )
    }

    /**
     * Construct and persist the session, run the Obsidian auto-export, then
     * write the dose/event children in one batch and drop deleted doses.
     * The caller navigates back after this returns.
     */
    fun save() {
        val now = currentTimeMillis()
        val sessionId = sessionToEdit?.id
            ?: "session:${now}_${Random.nextInt(0, 0x10000).toString(16)}"
        val useShulgin = repo.ratingScaleMode.value == RatingScaleMode.SHULGIN
        val session = Session(
            id = sessionId,
            title = title.value.ifBlank { "Untitled Session" },
            startTime = startTime.value,
            endTime = endTime.value?.let { if (it > 1000L && it != startTime.value) it else null },
            consumerName = consumerName.value.ifBlank { null },
            personId = personId.value,
            set = set.value.ifBlank { null },
            setting = setting.value.ifBlank { null },
            intention = intention.value.ifBlank { null },
            outcome = outcome.value.ifBlank { null },
            notes = notes.value.ifBlank { null },
            tags = tags.value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            rating = if (useShulgin) {
                shulginRating.value.let { s ->
                    ShulginRating.entries.find { it.name == s }?.numericValue
                }
            } else rating.value.toIntOrNull()?.coerceIn(1, 10),
            shulginRating = if (useShulgin) shulginRating.value.ifBlank { null } else null,
            checkins = sessionToEdit?.checkins ?: emptyList(),
            isFavorite = isFavorite.value,
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

        val doses = sessionDoses.value
        val events = sessionEvents.value
        val existingIds = if (sessionToEdit != null)
            repo.dosesForSession(sessionToEdit.id).map { it.id }.toSet() else emptySet()
        val keptIds = doses.map { it.id }.toSet()
        // One putAll per store + ONE index rebuild instead of a full reindex
        // per dose/event: a 10-dose/5-event save used to do 15 reindexes under
        // the repo lock on the UI thread. Draft-id re-parenting of doses and
        // events happens inside the repository.
        repo.upsertSessionChildren(sessionId, doses, events)
        (existingIds - keptIds).forEach { repo.deleteDose(it) }
    }

    /**
     * Purge events still bound to the draft id. Events are persisted on add,
     * so a discarded draft would otherwise leave orphaned rows pointing at a
     * session that never existed. No-op when editing a saved session.
     */
    fun discardDraft() {
        if (sessionToEdit == null) {
            repo.eventsForSession(draftSessionId).forEach { repo.deleteTimelineEvent(it.id) }
        }
    }

    /** Delete the open session (delete lives inside the editor, never on the list card). */
    fun deleteSession() {
        sessionToEdit?.let { repo.deleteSession(it.id) }
    }

    /** Add or replace a dose in the draft dose list. */
    fun upsertDose(dose: Dose) {
        val current = sessionDoses.value
        sessionDoses.value = if (current.any { it.id == dose.id })
            current.map { if (it.id == dose.id) dose else it }
        else current + dose
    }

    /** Drop a not-yet-saved dose from the draft list. */
    fun deleteDose(doseId: String) {
        sessionDoses.value = sessionDoses.value.filter { it.id != doseId }
    }

    /** Append an event added through the events section. */
    fun addEvent(event: TimelineEvent) {
        sessionEvents.value = sessionEvents.value + event
    }

    fun deleteEvent(eventId: String) {
        // Events are persisted on add, so deletion must hit the repo too,
        // otherwise the event reappears on reopen.
        repo.deleteTimelineEvent(eventId)
        sessionEvents.value = sessionEvents.value.filter { it.id != eventId }
    }

    /** Create an individual and assign it to this session. */
    fun createPerson(person: Person) {
        repo.upsertPerson(person)
        personId.value = person.id
    }

    companion object {
        fun create(repo: IJournalRepository, sessionToEdit: Session?): SessionEditorViewModel =
            SessionEditorViewModel(repo, sessionToEdit)
    }
}
