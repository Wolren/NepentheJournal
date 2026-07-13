package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.ingest.SubstanceClassNormalizer
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.readBundledResource
import kotlinx.coroutines.*

/**
 * Orchestrates data initialization on startup.
 *
 * Priority:
 * 1. Load bundled seed (substances + interactions) - always, to ensure latest data.
 * 2. Migrate old session/dose references from pwiki: IDs to cid: IDs.
 * 3. Load user data from disk (sessions, doses, notes, timeline events).
 * 4. If test mode and nothing loaded, generate fuzz session data.
 *
 * The seed is built by scripts/matrix_build.py from PsychonautWiki SMW +
 * PubChem + TripSit data. Run it monthly to refresh.
 *
 * Test mode: -Dnepenthe.test-data=true or NEPENTHE_TEST_DATA=1.
 */
object DataInitializer {

    private var initialized = false
    private var autoSaveJob: Job? = null

    private const val SEED_RESOURCE = "/psychonautwiki_seed.json"

    fun isTestDataEnabled(): Boolean {
        return try {
            java.lang.Boolean.getBoolean("nepenthe.test-data") ||
            System.getenv("NEPENTHE_TEST_DATA") == "1"
        } catch (_: Exception) { false }
    }

    /**
     * @param scope optional scope for auto-save coroutine. If null, auto-save is skipped.
     */
    fun ensureInitialized(repo: JournalRepository, scope: CoroutineScope? = null) {
        if (initialized) return
        initialized = true

        val store = JournalStore(repo)

        // Step 1: Load user data from disk first (to check for old IDs)
        store.load()

        // Step 2: Load bundled seed (substances + interactions).
        // Always done to refresh substance data on every startup.
        val seedLoaded = tryLoadSeed(repo)

        // Step 3: Ingest DoseWiki data (effects, supplementary info)
        DoseWikiIngestor.ensureIngested(repo)

        // Step 4: Migrate old session/dose references if ID scheme changed.
        if (seedLoaded) {
            migrateOldIds(repo)
            store.save()
        }

        val subCount = repo.substances.value.size
        val sessionCount = repo.sessions.value.size

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
    internal fun migrateOldIds(repo: JournalRepository) {
        // Build mapping: old pwiki:xxx ID -> new cid:xxxx ID
        val idMap = mutableMapOf<String, String>()
        for (sub in repo.substances.value) {
            val oldId = sub.oldId
            if (oldId != null && oldId != sub.id && (oldId.startsWith("pwiki:") || sub.id.startsWith("cid:"))) {
                idMap[oldId] = sub.id
            }
        }

        if (idMap.isEmpty()) {
            //println("No ID migration needed")
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

    private fun tryLoadSeed(repo: JournalRepository): Boolean {
        return try {
            val text = readBundledResource(SEED_RESOURCE)
                ?: run {
                    Log.withTag("DataInit").w { "Seed resource $SEED_RESOURCE not found" }
                    return false
                }
            val snapshot = JournalJson.json.decodeFromString<JournalSnapshot>(text)
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

    fun resetWithTestData(repo: JournalRepository) {
        repo.clearAll()
        tryLoadSeed(repo)
        FuzzSeed.generate(repo)
        val store = JournalStore(repo)
        store.save()
    }
}
