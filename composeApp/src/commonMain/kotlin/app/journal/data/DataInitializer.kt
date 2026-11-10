package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.ingest.DosewikiTaxonomy
import app.journal.ingest.SubstanceClassNormalizer
import app.journal.log.Log
import app.journal.model.*
import app.journal.model.rules.InteractionClassEnricher
import app.journal.util.PlatformLock
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
 * Order: user data from disk opens [initializedFlow]; the bundled library (PW
 * seed, DoseWiki, taxonomy) then builds on [libraryScope] and opens
 * [libraryReadyFlow]. Measured on an Android emulator: a 5.9 MB snapshot loads
 * in 4.7 s, the bundled library build takes 6.0 s.
 *
 * Priority (DoseWiki-first):
 * 1. Load user data from disk (sessions, doses, notes, timeline events).
 * 2. Load the bundled PW seed (substances + interactions) as the fallback base.
 * 3. Ingest DoseWiki data as the PRIMARY entity: matched substances are
 *    overwritten with DoseWiki fields, unmatched DoseWiki substances are
 *    created as dw:{slug} rows. DoseWiki wins every field it carries.
 * 4. Migrate old session/dose references from pwiki: IDs to cid: IDs.
 * 5. If test mode, generate fuzz session data.
 *
 * The PW seed is built by scripts/matrix_build.py from PsychonautWiki SMW +
 * PubChem + TripSit data. The DoseWiki slim bundle is built by
 * scripts/dosewiki_slim.py from https://dose.wiki open data. Refresh both
 * monthly.
 *
 * Test mode: -Dnepenthe.test-data=true or NEPENTHE_TEST_DATA=1.
 */
object DataInitializer {

    private const val SEED_RESOURCE = "/psychonautwiki_seed.json"

    private val guardLock = PlatformLock()
    private var initialized = false
    private var autoSaveJob: Job? = null
    private var libraryJob: Job? = null

    /** Application-lifetime scope: MainActivity cancels its own scope in onDestroy. */
    private val libraryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.withTag("DataInit").e(e) { "Library build coroutine failed" }
        }
    )

    private val _initializedFlow = MutableStateFlow(false)
    /**
     * True once user data is loaded and auto-save runs. The library build may still be running.
     */
    val initializedFlow: StateFlow<Boolean> = _initializedFlow.asStateFlow()

    private val _libraryReadyFlow = MutableStateFlow(false)
    /**
     * True once the seed, DoseWiki ingest and taxonomy tags are persisted. Gate
     * every surface that picks a substance on this.
     */
    val libraryReadyFlow: StateFlow<Boolean> = _libraryReadyFlow.asStateFlow()

    private val _phase = MutableStateFlow("Loading journal")

    /** Splash progress label: startup runs off the UI thread, so a silent splash reads as a freeze. */
    val phase: StateFlow<String> = _phase.asStateFlow()

    /** Test seam: a suspending hook at the start of the library build. */
    internal var libraryBuildHook: (suspend () -> Unit)? = null

    /** Test seam: a missing path forces the failed-seed-load path. */
    internal var seedResourcePath: String = SEED_RESOURCE

    internal fun reset() {
        guardLock.withLock {
            initialized = false
            autoSaveJob?.cancel()
            autoSaveJob = null
            libraryJob?.cancel()
            libraryJob = null
        }
        _phase.value = "Loading journal"
        _libraryReadyFlow.value = false
        _initializedFlow.value = false
    }

    fun isTestDataEnabled(): Boolean {
        return platformTestDataEnabled()
    }

    /**
     * @param scope optional scope for auto-save coroutine. If null, auto-save is skipped.
     */
    fun ensureInitialized(repo: IJournalRepository, scope: CoroutineScope? = null) {
        val alreadyStarted = guardLock.withLock {
            val fresh = !initialized
            initialized = true
            !fresh
        }
        if (alreadyStarted) return
        try {
            startJournal(repo, scope)
        } finally {
            _initializedFlow.value = true
        }
    }

    /** Suspends until the background library build finishes, including a failed one. */
    internal suspend fun awaitLibraryBuild() {
        libraryJob?.join()
    }

    /**
     * Synchronous startup: user data, auto-save and crash leftovers, then
     * either an immediate library-ready signal or the queued background build.
     * Every exit funnels through the caller's finally block, so
     * [initializedFlow] always opens the UI gate.
     */
    private fun startJournal(repo: IJournalRepository, scope: CoroutineScope?) {
        val store = JournalStore(repo as JournalRepository)
        val startedAt = currentTimeMillis()

        // Step 1: Load user data from disk first (to check for old IDs)
        _phase.value = "Reading saved journal"
        store.load()
        val loadMs = currentTimeMillis() - startedAt

        // Auto-save must be live before the gate opens: the welcome dialog mutates during the build.
        if (scope != null) {
            // Quiet-period saves skip the backup rotation; close and explicit saves rotate.
            autoSaveJob = repo.autoSave(scope) { store.save(fullBackup = false) }
            Log.withTag("DataInit").i { "Auto-save enabled (debounce 2000ms)" }
        }

        purgeResumeMarkers(repo, store)
        abortStaleLiveSessions(repo, store)

        // Step 2: Skip the bundled library when the persisted one matches the bundled resources.
        val libraryFresh = repo.seedFingerprint.value == SEED_BUNDLED_FINGERPRINT &&
            repo.substances.value.isNotEmpty()
        if (libraryFresh) {
            Log.withTag("DataInit").i { "Seed unchanged, skipping re-ingest" }
            // Taxonomy still runs here: a sync-imported dw: substance needs its curated tags.
            var libraryDirty = false
            _phase.value = "Applying taxonomy"
            val taggedCount = DosewikiTaxonomy.applyTags(repo)
            if (taggedCount > 0) {
                libraryDirty = true
                Log.withTag("DataInit").i { "Tagged $taggedCount substances with curated taxonomy" }
            }
            _phase.value = "Applying interaction classes"
            if (enrichInteractionClasses(repo) > 0) libraryDirty = true
            if (isTestDataEnabled() && repo.substances.value.size >= 2) {
                FuzzSeed.generate(repo)
                libraryDirty = true
            }
            if (libraryDirty) {
                _phase.value = "Saving library"
                store.save()
            }
            _phase.value = "Library ready"
            _libraryReadyFlow.value = true
            logStartup(repo, startedAt, loadMs, libraryMs = null)
            return
        }

        _phase.value = "Loading bundled library"
        libraryJob = libraryScope.launch { buildLibrary(repo, store, scope, startedAt, loadMs) }
    }

    /**
     * Background library build. Persists before opening [libraryReadyFlow]; a
     * failure logs, still opens the gate and leaves the app on the loaded data.
     */
    private suspend fun buildLibrary(
        repo: IJournalRepository,
        store: JournalStore,
        callerScope: CoroutineScope?,
        startedAt: Long,
        loadMs: Long,
    ) {
        val buildStartedAt = currentTimeMillis()
        try {
            libraryBuildHook?.invoke()

            val seedLoaded = tryLoadSeedWithRetry(repo)

            // Step 3: Ingest DoseWiki data as the primary entity (overwrites matched seed rows, creates dw:{slug} rows for the rest).
            _phase.value = "Merging DoseWiki data"
            DoseWikiIngestor.ensureIngested(repo)

            // Step 4: Migrate old session/dose references if ID scheme changed.
            if (seedLoaded) {
                migrateOldIds(repo)
                // Stamp the fingerprint only on success: a failed seed load must retry next launch instead of looking fresh.
                repo.setSeedFingerprint(SEED_BUNDLED_FINGERPRINT)
            }

            _phase.value = "Applying taxonomy"
            val taggedCount = DosewikiTaxonomy.applyTags(repo)
            if (taggedCount > 0) {
                Log.withTag("DataInit").i { "Tagged $taggedCount substances with curated taxonomy" }
            }

            _phase.value = "Applying interaction classes"
            enrichInteractionClasses(repo)

            // Re-arm auto-save so the build's own mutations do not queue a second full save.
            if (callerScope != null) {
                guardLock.withLock {
                    autoSaveJob?.cancel()
                    autoSaveJob = repo.autoSave(callerScope) { store.save(fullBackup = false) }
                }
            }

            // One write per launch: ingest and tagging used to save separately, doubling a multi-second encode on phones.
            _phase.value = "Saving library"
            store.save()

            // Step 5: If test mode, generate fuzz sessions on top of seed/disk data.
            if (isTestDataEnabled() && repo.substances.value.size >= 2) {
                FuzzSeed.generate(repo)
                store.save()
                Log.withTag("DataInit").i { "Generated fuzz test data (${repo.sessions.value.size} sessions, ${repo.substances.value.size} substances)" }
            }

            // A reset() during the final save must not reopen the gate for a cancelled build.
            currentCoroutineContext().ensureActive()
            _phase.value = "Library ready"
            logStartup(repo, startedAt, loadMs, libraryMs = currentTimeMillis() - buildStartedAt)
            _libraryReadyFlow.value = true
        } catch (e: CancellationException) {
            // reset() cancelled the build: a new launch rebuilds it, so the gate stays closed.
            _phase.value = "Library build cancelled"
            throw e
        } catch (e: Throwable) {
            Log.withTag("DataInit").e(e) { "Library build failed; opening the gate on the loaded data" }
            _phase.value = "Library unavailable"
            _libraryReadyFlow.value = true
        }
    }

    private fun logStartup(repo: IJournalRepository, startedAt: Long, loadMs: Long, libraryMs: Long?) {
        val totalMs = currentTimeMillis() - startedAt
        val library = libraryMs?.let { "${it}ms" } ?: "skipped"
        Log.withTag("DataInit").i { "Startup ${totalMs}ms: load ${loadMs}ms, library $library" }
        val subCount = repo.substances.value.size
        val sessionCount = repo.sessions.value.size
        if (subCount > 0 || sessionCount > 0) {
            Log.withTag("DataInit").i { "Initialized: $subCount substances, $sessionCount sessions" }
        }
    }

    private fun purgeResumeMarkers(repo: IJournalRepository, store: JournalStore) {
        val markers = repo.timelineEvents.value.filter {
            it.id.startsWith("event:pause:") || it.id.startsWith("event:resume:")
        }
        if (markers.isNotEmpty()) {
            markers.forEach { repo.deleteTimelineEvent(it.id) }
            store.save()
            Log.withTag("DataInit").i { "Purged ${markers.size} pause/resume marker events" }
        }
    }

    private fun abortStaleLiveSessions(repo: IJournalRepository, store: JournalStore) {
        // A live timer older than 1h with no logged doses is abandoned, not a trip: delete it.
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
    }

    /**
     * Adds the interaction classes the DoseWiki ingest drops, because the upstream
     * psychoactive labels name drug families the rule table does not speak.
     * Additive and idempotent: a launch after the first writes nothing.
     */
    internal fun enrichInteractionClasses(repo: IJournalRepository): Int {
        val updated = repo.substances.value.mapNotNull { substance ->
            val enriched = InteractionClassEnricher.enrich(substance)
            enriched.takeIf { it !== substance }
        }
        if (updated.isEmpty()) return 0
        // A newer peer copy wins, so startup enrichment never rewrites synced rows.
        repo.applyBatch(substances = updated, lastWriterWins = true)
        Log.withTag("DataInit").i { "Enriched interaction classes on ${updated.size} substances" }
        return updated.size
    }

    /**
     * Builds an oldId -> newId mapping from substances that have oldId set,
     * then patches any existing doses, interactions, or session data that
     * still reference the old IDs.
     */
    internal fun migrateOldIds(repo: IJournalRepository) {
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

        // Collect first, apply once (audit row "DataInitializer.kt:201,215"): the old loop upserted dose-by-dose and interaction-by-interaction, each a full mutation pass.
        var patchedDoses = 0
        val remappedDoses = mutableListOf<Dose>()
        for (dose in repo.doses.value) {
            val newId = idMap[dose.substanceId]
            if (newId != null && newId != dose.substanceId) {
                remappedDoses.add(dose.copy(substanceId = newId))
                patchedDoses++
            }
        }

        var patchedInteractions = 0
        val remappedInteractions = mutableListOf<Interaction>()
        for (interaction in repo.interactions.value) {
            val newA = idMap[interaction.substanceAId] ?: interaction.substanceAId
            val newB = idMap[interaction.substanceBId] ?: interaction.substanceBId
            if (newA != interaction.substanceAId || newB != interaction.substanceBId) {
                val sortedIds = listOf(newA, newB).sorted()
                remappedInteractions.add(
                    interaction.copy(
                        substanceAId = sortedIds[0],
                        substanceBId = sortedIds[1]
                    )
                )
                patchedInteractions++
            }
        }

        if (remappedDoses.isNotEmpty() || remappedInteractions.isNotEmpty()) {
            repo.applyBatch(doses = remappedDoses, interactions = remappedInteractions)
        }

        Log.withTag("DataInit").i { "  Patched $patchedDoses doses, $patchedInteractions interactions" }
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
            val text = readBundledResource(seedResourcePath)
                ?: run {
                    Log.withTag("DataInit").w { "Seed resource $seedResourcePath not found" }
                    return false
                }
            val snapshot = decodeSnapshot(text).getOrThrow()
            val normalizedSnapshot = snapshot.copy(
                substances = snapshot.substances.map { sub ->
                    sub.copy(substanceClass = SubstanceClassNormalizer.normalize(sub.substanceClass))
                }
            )
            repo.applySnapshot(normalizedSnapshot)
            val count = normalizedSnapshot.substances.size
            val distinctBefore = snapshot.substances.flatMap { it.substanceClass }.distinct().size
            val distinctAfter = normalizedSnapshot.substances.flatMap { it.substanceClass }.distinct().size
            Log.withTag("DataInit").i { "Normalized substance classes: $distinctBefore -> $distinctAfter distinct labels" }
            if (count > 0) {
                Log.withTag("DataInit").i { "Loaded $count substances from bundled seed ($seedResourcePath)" }
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
        // DoseWikiIngestor.ingested is unsynchronized; reloading during the background build tears the ingest.
        if (!libraryReadyFlow.value) {
            Log.withTag("DataInit").w { "Reload defaults ignored: the bundled library build is still running" }
            return
        }
        try {
            val text = readBundledResource(SEED_RESOURCE) ?: return
            val snapshot = decodeSnapshot(text).getOrThrow()
            val normalized = snapshot.copy(
                substances = snapshot.substances.map { sub ->
                    sub.copy(substanceClass = SubstanceClassNormalizer.normalize(sub.substanceClass))
                }
            )

            repo.applyBatch(substances = normalized.substances)

            DoseWikiIngestor.reset()
            DoseWikiIngestor.ensureIngested(repo)
            enrichInteractionClasses(repo)

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
