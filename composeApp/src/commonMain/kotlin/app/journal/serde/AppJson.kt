package app.journal.serde

import app.journal.data.IJournalRepository
import app.journal.data.JournalSnapshot
import app.journal.log.Log
import app.journal.model.ClockStamped
import app.journal.model.RatingScaleMode
import app.journal.util.Hlc
import app.journal.util.currentTimeMillis
import kotlinx.serialization.json.Json

/**
 * Canonical JSON serialization config for the entire app.
 * Used by persistence, sync, and export.
 *
 * Lives in the neutral `serde` package so every layer (data, ingest, model,
 * util, sync) can depend on it without a layering inversion or a
 * data <-> ingest import cycle.
 *
 * Config: ignoreUnknownKeys=true (forward compat), encodeDefaults=true (consistent wire format).
 * For human-readable output (export, Obsidian), use AppJson.pretty instead.
 */
object AppJson {
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun snapshot(repo: IJournalRepository): JournalSnapshot {
        return JournalSnapshot(
            savedAt = currentTimeMillis(),
            sessions = repo.sessions.value,
            substances = repo.substances.value,
            doses = repo.doses.value,
            notes = repo.notes.value,
            timelineEvents = repo.timelineEvents.value,
            interactions = repo.interactions.value,
            effects = repo.effects.value,
            customUnits = repo.customUnits.value,
            persons = repo.persons.value,
            medications = repo.medications.value,
            tombstones = repo.exportTombstones(),
            ratingScaleMode = repo.ratingScaleMode.value,
            useShulginRating = false,
            useSubstanceColors = repo.useSubstanceColors.value,
            welcomeCompleted = repo.welcomeCompleted.value,
            seedFingerprint = repo.seedFingerprint.value,
            obsidianVaultPath = repo.obsidianVaultPath.value,
            obsidianAutoExport = repo.obsidianAutoExport.value,
            obsidianSubfolder = repo.obsidianSubfolder.value,
            obsidianFileOrganization = repo.obsidianFileOrganization.value,
            showSessionsTrendChart = repo.showSessionsTrendChart.value,
            startTab = repo.startTab.value,
            timeDisplayMode = repo.timeDisplayMode.value,
            dashboardHeatmap = repo.dashboardHeatmap.value,
            dashboardTolerance = repo.dashboardTolerance.value,
            dashboardTopSubstances = repo.dashboardTopSubstances.value,
            confirmDeletes = repo.confirmDeletes.value,
            autosaveDelay = repo.autosaveDelay.value,
            autoBackupOnClose = repo.autoBackupOnClose.value
        )
    }

    /**
     * Load a snapshot into [repo].
     *
     * Version 10 added the hybrid logical clock; any blank clock column is filled with
     * "<updatedAt>:0", which keeps the old updatedAt order on that row until the next
     * local write takes a fresh stamp. Version 11 adds the per-field clock map on
     * sessions, doses and notes; a file from an older version loads with it empty and
     * the merge falls back to the row clock. This runs for every version, including a
     * future file and a recovered salvage, where a row can arrive with the field absent.
     */
    fun apply(repo: IJournalRepository, snapshot: JournalSnapshot) {
        when (val version = snapshot.version) {
            JournalSnapshot.CURRENT_VERSION -> Unit
            in 1 until JournalSnapshot.CURRENT_VERSION -> {
                Log.withTag("JournalStore").w {
                    "JournalSnapshot version $version is older than app version " +
                        "${JournalSnapshot.CURRENT_VERSION}; deriving clock stamps from updatedAt"
                }
            }
            else -> {
                Log.withTag("JournalStore").w {
                    "JournalSnapshot version $version does not match any known format " +
                        "(app version ${JournalSnapshot.CURRENT_VERSION}): file is corrupt or was written " +
                        "by a future app version. Loading best-effort; unknown fields are ignored."
                }
            }
        }
        val data = snapshot.withDerivedClocks()
        repo.applyBatch(
            sessions = data.sessions,
            substances = data.substances,
            doses = data.doses,
            notes = data.notes,
            timelineEvents = data.timelineEvents,
            interactions = data.interactions,
            effects = data.effects,
            customUnits = data.customUnits,
            persons = data.persons,
            medications = data.medications
        )
        repo.importTombstones(data.tombstones)
        repo.setRatingScaleMode(
            data.ratingScaleMode
                ?: if (data.useShulginRating) RatingScaleMode.SHULGIN else RatingScaleMode.OFF,
        )
        repo.setSubstanceColors(data.useSubstanceColors)
        repo.setWelcomeCompleted(data.welcomeCompleted)
        repo.setSeedFingerprint(data.seedFingerprint)
        repo.setObsidianVaultPath(data.obsidianVaultPath)
        repo.setObsidianAutoExport(data.obsidianAutoExport)
        repo.setObsidianSubfolder(data.obsidianSubfolder)
        repo.setObsidianFileOrganization(data.obsidianFileOrganization)
        repo.setShowSessionsTrendChart(data.showSessionsTrendChart)
        repo.setStartTab(data.startTab)
        repo.setTimeDisplayMode(data.timeDisplayMode)
        repo.setDashboardHeatmap(data.dashboardHeatmap)
        repo.setDashboardTolerance(data.dashboardTolerance)
        repo.setDashboardTopSubstances(data.dashboardTopSubstances)
        repo.setConfirmDeletes(data.confirmDeletes)
        repo.setAutosaveDelay(data.autosaveDelay)
        repo.setAutoBackupOnClose(data.autoBackupOnClose)
    }
}

/**
 * Fill any blank clock column. A pre-v10 snapshot has none; a recovered salvage or a
 * future file can also arrive with the field absent. Stamped rows are left untouched.
 */
private fun JournalSnapshot.withDerivedClocks(): JournalSnapshot = copy(
    sessions = sessions.deriveClocks { row, clock -> row.copy(clock = clock) },
    doses = doses.deriveClocks { row, clock -> row.copy(clock = clock) },
    notes = notes.deriveClocks { row, clock -> row.copy(clock = clock) },
    substances = substances.deriveClocks { row, clock -> row.copy(clock = clock) },
    effects = effects.deriveClocks { row, clock -> row.copy(clock = clock) },
    interactions = interactions.deriveClocks { row, clock -> row.copy(clock = clock) },
    timelineEvents = timelineEvents.deriveClocks { row, clock -> row.copy(clock = clock) },
    customUnits = customUnits.deriveClocks { row, clock -> row.copy(clock = clock) },
)

private fun <T : ClockStamped> List<T>.deriveClocks(copy: (T, String) -> T): List<T> =
    map { row ->
        if (row.clock.isNotBlank()) row else copy(row, Hlc.of(row.updatedAt).encoded)
    }
