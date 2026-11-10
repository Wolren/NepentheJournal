package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.model.Session
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Startup gate ordering: the journal opens while the bundled library build runs
 * in the background, the substance gate opens only after that build persisted,
 * and a failed seed load opens both so the app stays usable.
 */
class DataInitializerLibraryGateTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-test-library-gate-${System.nanoTime()}"
        )
        tmpDir.mkdirs()
        val origHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpDir.absolutePath)
        try {
            test(tmpDir.absolutePath)
        } finally {
            System.setProperty("user.home", origHome)
            tmpDir.deleteRecursively()
        }
    }

    @BeforeTest
    fun resetBefore() {
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    @AfterTest
    fun resetAfter() {
        DataInitializer.libraryBuildHook = null
        DataInitializer.seedResourcePath = "/psychonautwiki_seed.json"
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    @Test
    fun journalGateOpensWhileTheLibraryBuildIsStillRunning() = withTempHome { _ ->
        val release = CompletableDeferred<Unit>()
        DataInitializer.libraryBuildHook = { release.await() }
        val repo = JournalRepository()

        DataInitializer.ensureInitialized(repo)

        assertTrue(DataInitializer.initializedFlow.value,
            "initializedFlow must open before the bundled library build finishes")
        assertFalse(DataInitializer.libraryReadyFlow.value,
            "libraryReadyFlow must stay shut while the build runs")
        assertTrue(repo.substances.value.isEmpty(),
            "the paused build must not have applied the seed yet")
        assertEquals("Loading bundled library", DataInitializer.phase.value,
            "the phase must name the step that runs")

        release.complete(Unit)
        runBlocking { withTimeout(120_000) { DataInitializer.awaitLibraryBuild() } }

        assertTrue(DataInitializer.libraryReadyFlow.value,
            "libraryReadyFlow must open once the build finished")
        assertTrue(repo.substances.value.isNotEmpty(), "the build must apply the bundled seed")
    }

    @Test
    fun libraryGateOpensOnlyAfterTheBuildPersisted() = withTempHome { _ ->
        val repo = JournalRepository()

        DataInitializer.ensureInitialized(repo)
        runBlocking { withTimeout(120_000) { DataInitializer.awaitLibraryBuild() } }

        assertTrue(DataInitializer.libraryReadyFlow.value)
        val path = JournalStore(repo).dataPath()
        val snapshot = File(path).readMergedJournal()
        assertEquals(repo.substances.value.size, snapshot.substances.size,
            "the persisted library must match memory when the gate opens")
        assertNotNull(snapshot.seedFingerprint,
            "the stamped fingerprint must be on disk before the gate opens")
    }

    @Test
    fun failedSeedLoadOpensBothGatesAndLeavesTheAppUsable() = withTempHome { _ ->
        DataInitializer.seedResourcePath = "/missing-seed-${System.nanoTime()}.json"
        val repo = JournalRepository()

        DataInitializer.ensureInitialized(repo)
        assertTrue(DataInitializer.initializedFlow.value,
            "a failed seed load must still open the journal gate")
        runBlocking { withTimeout(120_000) { DataInitializer.awaitLibraryBuild() } }
        assertTrue(DataInitializer.libraryReadyFlow.value,
            "a failed seed load must still open the library gate")

        val stamp = System.currentTimeMillis()
        repo.upsertSession(
            Session(
                id = "s:after-failed-build", createdAt = stamp, updatedAt = stamp,
                deviceOrigin = "test", title = "Usable after a failed build",
                startTime = stamp, endTime = stamp + 60_000L
            )
        )
        JournalStore(repo).save()
        assertTrue(
            File(JournalStore(repo).dataPath()).readJournal().contains("s:after-failed-build"),
            "the journal must stay writable after a failed library build"
        )
    }
}
