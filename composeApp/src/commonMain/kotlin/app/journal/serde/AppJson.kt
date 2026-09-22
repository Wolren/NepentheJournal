package app.journal.serde

import app.journal.data.IJournalRepository
import app.journal.data.JournalSnapshot
import app.journal.log.Log
import app.journal.model.RatingScaleMode
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

    /** Pretty-printed variant for user-facing export files. */
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
            showSessionsTrendChart = repo.showSessionsTrendChart.value
        )
    }

    fun apply(repo: IJournalRepository, snapshot: JournalSnapshot) {
        if (snapshot.version != JournalSnapshot.CURRENT_VERSION) {
            Log.withTag("JournalStore").w { "JournalSnapshot version mismatch: file v${snapshot.version}, app v${JournalSnapshot.CURRENT_VERSION}. Data may not load correctly." }
        }
        repo.applyBatch(
            sessions = snapshot.sessions,
            substances = snapshot.substances,
            doses = snapshot.doses,
            notes = snapshot.notes,
            timelineEvents = snapshot.timelineEvents,
            interactions = snapshot.interactions,
            effects = snapshot.effects,
            customUnits = snapshot.customUnits,
            // Persons are device-local snapshot data (never synced): snapshot loads
            // must restore them, while sync deltas leave the default empty.
            persons = snapshot.persons
        )
        repo.importTombstones(snapshot.tombstones)
        repo.setRatingScaleMode(
            snapshot.ratingScaleMode
                ?: if (snapshot.useShulginRating) RatingScaleMode.SHULGIN else RatingScaleMode.OFF,
        )
        repo.setSubstanceColors(snapshot.useSubstanceColors)
        repo.setWelcomeCompleted(snapshot.welcomeCompleted)
        repo.setSeedFingerprint(snapshot.seedFingerprint)
        repo.setObsidianVaultPath(snapshot.obsidianVaultPath)
        repo.setObsidianAutoExport(snapshot.obsidianAutoExport)
        repo.setObsidianSubfolder(snapshot.obsidianSubfolder)
        repo.setObsidianFileOrganization(snapshot.obsidianFileOrganization)
        repo.setShowSessionsTrendChart(snapshot.showSessionsTrendChart)
    }
}
