package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.ingest.DosewikiTaxonomy
import app.journal.ingest.SubstanceClassNormalizer
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.crypto.sha256
import app.journal.util.currentTimeMillis
import app.journal.util.platformTestDataEnabled
import app.journal.util.readBundledResource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _initializedFlow = MutableStateFlow(false)
    /**
     * True once [ensureInitialized] has fully completed. The UI shows a
     * loading gate until then so slow first-launch ingests never present
     * a blank screen.
     */
    val initializedFlow: StateFlow<Boolean> = _initializedFlow.asStateFlow()

    /** Reset internal state so the next [ensureInitialized] call re-runs initialization. */
    internal fun reset() {
        initialized = false
        autoSaveJob = null
        _initializedFlow.value = false
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
        try {
            runInitialization(repo, scope)
        } finally {
            _initializedFlow.value = true
        }
    }

    /**
     * Body of [ensureInitialized]. Every exit funnels through the caller's
     * finally block so [initializedFlow] always opens the UI gate, even
     * when a corrupt snapshot or failing resource throws mid-init.
     */
    private fun runInitialization(repo: IJournalRepository, scope: CoroutineScope?) {
        val store = JournalStore(repo as JournalRepository)

        // Step 1: Load user data from disk first (to check for old IDs)
        store.load()

        // Step 2: Load bundled PW seed + DoseWiki ingest, unless the
        // persisted library already matches the bundled resources. Parsing
        // and ingesting megabytes of JSON on every launch is what kept slow
        // devices on a blank screen for minutes; the fingerprint makes the
        // steady state a plain disk load.
        val bundledFingerprint = seedBundledFingerprint()
        val libraryFresh = bundledFingerprint != null &&
            repo.seedFingerprint.value == bundledFingerprint &&
            repo.substances.value.isNotEmpty()
        if (libraryFresh) {
            Log.withTag("DataInit").i { "Seed unchanged, skipping re-ingest" }
        } else {
            val seedLoaded = tryLoadSeedWithRetry(repo)

            // Step 3: Ingest DoseWiki data as the PRIMARY entity (overwrites
            // matched seed rows, creates dw:{slug} rows for the rest).
            DoseWikiIngestor.ensureIngested(repo)

            // Step 4: Migrate old session/dose references if ID scheme changed.
            if (seedLoaded) {
                migrateOldIds(repo)
            }
            // Stamp the fingerprint only on success: a failed seed load must
            // retry next launch instead of looking fresh with a stale library.
            if (seedLoaded && bundledFingerprint != null) {
                repo.setSeedFingerprint(bundledFingerprint)
            }
            store.save()
        }

        val subCount = repo.substances.value.size
        val sessionCount = repo.sessions.value.size

        // Curated DoseWiki taxonomy tags. Cheap and idempotent: steady-state
        // launches change nothing and skip the save.
        val taggedCount = DosewikiTaxonomy.applyTags(repo)
        if (taggedCount > 0) {
            store.save()
            Log.withTag("DataInit").i { "Saved curated taxonomy tags ($taggedCount substances)" }
        }

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

        // Abort stale empty live sessions. A live timer older than 1h with no
        // logged doses is abandoned, not a trip: delete so dead timers stop
        // piling up behind the single-live cap.
        val staleCutoff = currentTimeMillis() - 3_600_000L
        val staleLive = repo.sessions.value.filter {
            it.id.startsWith("session:live:") && it.endTime == null &&
                it.startTime < staleCutoff && repo.dosesForSession(it.id).isEmpty()
        }
        if (staleLive.isNotEmpty()) {
            staleLive.forEach { repo.deleteSession(it.id) }
            store.save()
            Log.withTag("DataInit").i { "Aborted ${staleLive.size} stale empty live sessions" }
        }

        // Step 4: If test mode, generate fuzz sessions on top of seed/disk data.
        if (isTestDataEnabled() && subCount >= 2) {
            FuzzSeed.generate(repo)
            store.save()
            Log.withTag("DataInit").i { "Generated fuzz test data (${repo.sessions.value.size} sessions, ${repo.substances.value.size} substances)" }
        }

        // Step 5: Wire debounced auto-save (4.1): saves 2s after every mutation
        if (scope != null) {
            autoSaveJob = repo.autoSave(scope) { store.save() }
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
                // Preserve the original interaction ID: it's just a unique key,
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

    /**
     * Fingerprint of the bundled resources: lengths plus SHA-256 over the
     * raw seed and DoseWiki texts. Null when either resource is missing,
     * which fails open into a full re-ingest. Raw text reads are cheap;
     * the parse plus ingest they gate is what stalls slow devices.
     */
    private fun seedBundledFingerprint(): String? {
        val seedText = readBundledResource(SEED_RESOURCE) ?: return null
        val doseText = DoseWikiIngestor.bundledText() ?: return null
        val hex = sha256((seedText + doseText).encodeToByteArray())
            .joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
        return "${seedText.length}:${doseText.length}:$hex"
    }

    /**
     * Seed load with one retry: a transient read or parse failure on first
     * launch must not leave a fresh install with an empty library.
     */
    private fun tryLoadSeedWithRetry(repo: IJournalRepository): Boolean {
        if (tryLoadSeed(repo)) return true
        Log.withTag("DataInit").w { "Seed load failed, retrying once" }
        return tryLoadSeed(repo)
    }

    private fun tryLoadSeed(repo: IJournalRepository): Boolean {
        return try {
            val text = readBundledResource(SEED_RESOURCE)
                ?: run {
                    Log.withTag("DataInit").w { "Seed resource $SEED_RESOURCE not found" }
                    return false
                }
            // Strict shared decode (no recovery fallback): a corrupt bundled seed
            // must fail this launch and retry next time, never half-load.
            val snapshot = decodeSnapshot(text).getOrThrow()
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
            // Same strict shared decode as tryLoadSeed; the outer catch keeps
            // reporting the failure exactly as before.
            val snapshot = decodeSnapshot(text).getOrThrow()
            val normalized = snapshot.copy(
                substances = snapshot.substances.map { sub ->
                    sub.copy(substanceClass = SubstanceClassNormalizer.normalize(sub.substanceClass))
                }
            )

            // Only touch substances: preserve sessions, doses, settings, etc.
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
