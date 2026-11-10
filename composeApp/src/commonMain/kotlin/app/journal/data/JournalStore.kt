package app.journal.data

import app.journal.model.*
import kotlinx.serialization.Serializable

/**
 * Full serializable snapshot of the journal's data store.
 * Used for JSON file persistence and backup/restore.
 */
@Serializable
data class JournalSnapshot(
    val version: Int = CURRENT_VERSION,
    val savedAt: Long,
    val sessions: List<Session> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList(),
    val persons: List<Person> = emptyList(),
    /**
     * Standing medications fed into the interaction check. Device-local for the
     * same reason people are: personal medical data stays on this device.
     */
    val medications: List<Medication> = emptyList(),
    val tombstones: Map<String, Long> = emptyMap(),
    /**
     * Rating scale mode. Null means an old file predating the mode:
     * fall back to [useShulginRating].
     */
    val ratingScaleMode: RatingScaleMode? = null,
    val useShulginRating: Boolean = false,
    val useSubstanceColors: Boolean = true,
    val welcomeCompleted: Boolean = false,
    /**
     * Fingerprint of the bundled resources the substance library was last
     * ingested from (see IJournalRepository.seedFingerprint). Null means
     * unknown: ingest unconditionally.
     */
    val seedFingerprint: String? = null,
    val obsidianVaultPath: String = "",
    val obsidianAutoExport: Boolean = false,
    val obsidianSubfolder: String = "Nepenthe",
    val obsidianFileOrganization: String = "flat",
    val showSessionsTrendChart: Boolean = false
) {
    companion object {
        const val CURRENT_VERSION = 8
    }
}

/**
 * Handles loading/saving repository state to two JSON files: the user journal
 * (`journal-data.json`) and the library (`journal-library.json`). Each file is
 * sealed at rest; the library file is only rewritten when the library changes.
 * Platform-specific path resolution via expect/actual.
 *
 * Depends on the [IJournalRepository] abstraction so the composition root can
 * inject any repository implementation (audit C5).
 */
expect class JournalStore(repo: IJournalRepository) {
    /**
     * Persist the repository to disk (atomic tmp + rename), plus the library file
     * when its version moved.
     *
     * @param fullBackup when true (default), also rotate the versioned .bak.N
     * chain. Every save refreshes the immediate .bak; pass false for the
     * high-frequency autosave path.
     */
    fun save(fullBackup: Boolean = true)
    fun load()
    fun dataPath(): String

    var lastLoadHadIssues: Boolean
        private set

    var lastLoadIssueSummary: String
        private set

    /**
     * Creates a timestamped auto-backup in the .auto/ subdirectory.
     * Platform-specific: may also rotate old backups.
     */
    fun triggerAutoBackup()

    /**
     * Replaces the main journal file from the .bak backup and reloads.
     * Returns true if restore succeeded, false if no .bak was available.
     */
    fun restoreFromBackup(): Boolean
}
