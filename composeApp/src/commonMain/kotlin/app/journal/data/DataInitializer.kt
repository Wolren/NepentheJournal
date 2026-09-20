package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.ingest.SubstanceClassNormalizer
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.platformTestDataEnabled
import app.journal.util.readBundledResource
import kotlinx.coroutines.*

/**
 * Orchestrates data initialization on startup.
 *
 * Priority (DoseWiki-first):
 * 1. Load bundled PW seed (substances + interactions) as the fallback base.
 * 2. Ingest DoseWiki data as the PRIMARY entity: matched substances are
 *    overwritten with DoseWiki fields, unmatched DoseWiki substances are
 *    created as dw:{slug} rows. DoseWiki wins every field it carries.
 * 3. Migrate old session/dose references from pwiki: IDs to cid: IDs.
 * 4. Load user data from disk (sessions, doses, notes, timeline events).
 * 5. If test mode and nothing loaded, generate fuzz session data.
 *
 * The PW seed is built by scripts/matrix_build.py from PsychonautWiki SMW +
 * PubChem + TripSit data. The DoseWiki slim bundle is built by
 * scripts/dosewiki_slim.py from https://dose.wiki open data. Refresh both
 * monthly.
 *
 * Test mode: -Dnepenthe.test-data=true or NEPENTHE_TEST_DATA=1.
 */
object DataInitializer {

    private var initialized = false
    private var autoSaveJob: Job? = null

    /** Reset internal state so the next [ensureInitialized] call re-runs initialization. */
    internal fun reset() {
        initialized = false
        autoSaveJob = null
    }

    private const val SEED_RESOURCE = "/psychonautwiki_seed.json"

    fun isTestDataEnabled(): Boolean {
        return platformTestDataEnabled()
    }

    /**
     * @param scope optional scope for auto-save coroutine. If null, auto-save is skipped.
     */
    fun ensureInitialized(repo: IJournalRepository, scope: CoroutineScope? = null) {
        if (initialized) return
        initialized = true

        val store = JournalStore(repo as JournalRepository)

        // Step 1: Load user data from disk first (to check for old IDs)
        store.load()

        // Step 2: Load bundled PW seed (substances + interactions) as the
        // fallback base. Always done to refresh substance data on startup.
        val seedLoaded = tryLoadSeed(repo)

        // Step 3: Ingest DoseWiki data as the PRIMARY entity (overwrites
        // matched seed rows, creates dw:{slug} rows for the rest).
        DoseWikiIngestor.ensureIngested(repo)

        // Step 4: Migrate old session/dose references if ID scheme changed.
        if (seedLoaded) {
            migrateOldIds(repo)
            store.save()
        }

        val subCount = repo.substances.value.size
        val sessionCount = repo.sessions.value.size

        // Purge legacy pause/resume marker notes. Pause used to write NOTE
        // "Paused"/"Resumed" events; timer state lives on Session now, so the
        // markers are pure noise in timelines and exports.
        val markers = repo.timelineEvents.value.filter {
            it.id.startsWith("event:pause:") || it.id.startsWith("event:resume:")
        }
        if (markers.isNotEmpty()) {
            markers.forEach { repo.deleteTimelineEvent(it.id) }
            store.save()
            Log.withTag("DataInit").i { "Purged ${markers.size} pause/resume marker events" }
        }

        // Step 4: If test mode, generate fuzz sessions on top of seed/disk data.
        if (isTestDataEnabled() && subCount >= 2) {
            FuzzSeed.generate(repo)
            store.save()
            Log.withTag("DataInit").i { "Generated fuzz test data (${repo.sessions.value.size} sessions, ${repo.substances.value.size} substances)" }
        }

        // Step 5: Wire debounced auto-save (4.1) — saves 2s after every mutation
        if (scope != null) {
            autoSaveJob = repo.autoSave(store, scope)
            Log.withTag("DataInit").i { "Auto-save enabled (debounce 2000ms)" }
        }

        // Rebuild query indices after loading everything
        // (applySnapshot already rebuilds indices; incremental mutations
        // from DoseWikiIngestor and migrateOldIds maintain them.)

        if (subCount > 0 || sessionCount > 0) {
        Log.withTag("DataInit").i { "Initialized: $subCount substances, $sessionCount sessions" }
        }
    }

    /**
     * Builds an oldId -> newId mapping from substances that have oldId set,
     * then patches any existing doses, interactions, or session data that
     * still reference the old IDs.
     */
    internal fun migrateOldIds(repo: IJournalRepository) {
        // Build mapping: old pwiki:xxx ID -> new cid:xxxx ID
        val idMap = mutableMapOf<String, String>()
        for (sub in repo.substances.value) {
            val oldId = sub.oldId
            if (oldId != null && oldId != sub.id && (oldId.startsWith("pwiki:") || sub.id.startsWith("cid:"))) {
                idMap[oldId] = sub.id
            }
        }

        if (idMap.isEmpty()) {
            return
        }

        Log.withTag("DataInit").i { "Migrating ${idMap.size} substance ID mappings..." }

        // Patch doses that reference old IDs
        var patchedDoses = 0
        for (dose in repo.doses.value) {
            val newId = idMap[dose.substanceId]
            if (newId != null && newId != dose.substanceId) {
                repo.upsertDose(dose.copy(substanceId = newId))
                patchedDoses++
            }
        }

        // Patch interactions that reference old IDs
        var patchedInteractions = 0
        for (interaction in repo.interactions.value) {
            val newA = idMap[interaction.substanceAId] ?: interaction.substanceAId
            val newB = idMap[interaction.substanceBId] ?: interaction.substanceBId
            if (newA != interaction.substanceAId || newB != interaction.substanceBId) {
                val sortedIds = listOf(newA, newB).sorted()
                // Preserve the original interaction ID — it's just a unique key,
                // the canonical pairing is defined by the substanceAId/substanceBId fields.
                repo.upsertInteraction(
                    interaction.copy(
                        substanceAId = sortedIds[0],
                        substanceBId = sortedIds[1]
                    )
                )
                patchedInteractions++
            }
        }

        Log.withTag("DataInit").i { "  Patched $patchedDoses doses, $patchedInteractions interactions" }
    }

    private fun tryLoadSeed(repo: IJournalRepository): Boolean {
        return try {
            val text = readBundledResource(SEED_RESOURCE)
                ?: run {
                    Log.withTag("DataInit").w { "Seed resource $SEED_RESOURCE not found" }
                    return false
                }
            val snapshot = AppJson.json.decodeFromString<JournalSnapshot>(text)
            // Normalize substance classes (case, plural, joined-string cleanup)
            val normalizedSnapshot = snapshot.copy(
                substances = snapshot.substances.map { sub ->
                    sub.copy(substanceClass = SubstanceClassNormalizer.normalize(sub.substanceClass))
                }
            )
            repo.applySnapshot(normalizedSnapshot)
            val count = normalizedSnapshot.substances.size
            // Report class normalization stats
            val distinctBefore = snapshot.substances.flatMap { it.substanceClass }.distinct().size
            val distinctAfter = normalizedSnapshot.substances.flatMap { it.substanceClass }.distinct().size
            Log.withTag("DataInit").i { "Normalized substance classes: $distinctBefore -> $distinctAfter distinct labels" }
            if (count > 0) {
                Log.withTag("DataInit").i { "Loaded $count substances from bundled seed ($SEED_RESOURCE)" }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.withTag("DataInit").e { "Failed to load seed resource: ${e.message}" }
            false
        }
    }

    /**
     * Reload the default substance database from the bundled seed, discarding
     * any user modifications to preloaded substances and restoring factory
     * substance data. User-created substances (deviceOrigin != "system")
     * are preserved. Sessions, doses, notes, and timeline events are untouched.
     *
     * Call this when the user wants to reset the substance library to defaults.
     */
    fun reloadDefaultSubstances(repo: IJournalRepository) {
        try {
            val text = readBundledResource(SEED_RESOURCE) ?: return
            val snapshot = AppJson.json.decodeFromString<JournalSnapshot>(text)
            val normalized = snapshot.copy(
                substances = snapshot.substances.map { sub ->
                    sub.copy(substanceClass = SubstanceClassNormalizer.normalize(sub.substanceClass))
                }
            )

            // Only touch substances — preserve sessions, doses, settings, etc.
            // Seed substances (deviceOrigin == "system") get overwritten by ID.
            // User-created substances are also overwritten if they share an ID;
            // substances with IDs not in the seed survive untouched.
            repo.applyBatch(substances = normalized.substances)

            // Re-apply DoseWiki on top so it stays the primary entity.
            DoseWikiIngestor.reset()
            DoseWikiIngestor.ensureIngested(repo)

            JournalStore(repo as JournalRepository).save()
            Log.withTag("DataInit").i { "Reloaded ${normalized.substances.size} substances from bundled seed" }
        } catch (e: Exception) {
            Log.withTag("DataInit").e(e) { "Failed to reload default substances" }
        }
    }

    fun resetWithTestData(repo: IJournalRepository) {
        repo.clearAll()
        tryLoadSeed(repo)
        FuzzSeed.generate(repo)
        val store = JournalStore(repo as JournalRepository)
        store.save()
    }
}
