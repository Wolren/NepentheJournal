package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DataInitializer.ensureInitialized pipeline coverage (audit section 4, item
 * 4): seed loads exactly once, a second call is a hard no-op, and the
 * initializedFlow UI gate opens no matter how initialization exits (including
 * a throwing store.load: JournalStoreDesktop.load swallows its own
 * exceptions and reports them through lastLoadHadIssues, so the corrupt-file
 * test below proves the gate opens on that path, and the throwing-repo test
 * proves the finally block opens it for a propagating exception).
 *
 * Uses the withTempHome pattern from JournalStoreTest: user.home redirects to
 * a throwaway directory so the real ~/.nepenthe store is never touched.
 */
class DataInitializerTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-test-init-${System.nanoTime()}")
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

    /** Runs startup and waits for the bundled library build, which is asynchronous by design. */
    private fun ensureInitialized(repo: IJournalRepository) {
        DataInitializer.ensureInitialized(repo)
        runBlocking { withTimeout(120_000) { DataInitializer.awaitLibraryBuild() } }
    }

    @BeforeTest
    fun resetBefore() {
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    @AfterTest
    fun resetAfter() {
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    @Test
    fun seedLoadsOnceAndSecondEnsureIsNoop() = withTempHome { _ ->
        val repo = JournalRepository()

        ensureInitialized(repo)
        assertTrue(repo.substances.value.isNotEmpty(), "first call must load the bundled seed")
        assertNotNull(repo.seedFingerprint.value,
            "a successful seed load stamps the fingerprint")
        assertTrue(DataInitializer.initializedFlow.value,
            "initializedFlow must be true after a completed init")

        repo.clearAll()
        assertTrue(repo.substances.value.isEmpty(), "fixture check")

        ensureInitialized(repo)
        assertTrue(repo.substances.value.isEmpty(),
            "second ensureInitialized must be a no-op (a re-run repopulates the seed)")
        assertTrue(DataInitializer.initializedFlow.value, "gate stays open")
    }

    @Test
    fun gateOpensEvenWhenStoreLoadFailsOnCorruptFile() = withTempHome { home ->
        val dataFile = File(home, ".nepenthe${File.separator}journal-data.json")
        dataFile.parentFile.mkdirs()
        dataFile.writeText("{ definitely not json")

        val probe = JournalStore(JournalRepository())
        probe.load()
        assertTrue(probe.lastLoadHadIssues,
            "corrupt journal file must trip the store's load-recovery path")

        val repo = JournalRepository()
        ensureInitialized(repo)
        assertTrue(DataInitializer.initializedFlow.value,
            "initializedFlow must open even when store.load() fails")
        assertTrue(repo.substances.value.isNotEmpty(), "seed still loads")

        JournalStore(repo).save()
        assertTrue(dataFile.readJournal().contains("substances"),
            "init + save must leave a valid journal file")
    }

    @Test
    fun gateOpensEvenWhenInitializationThrows() = withTempHome { _ ->
        assertFalse(DataInitializer.initializedFlow.value, "reset must close the gate first")

        val neverCalled = Proxy.newProxyInstance(
            IJournalRepository::class.java.classLoader,
            arrayOf(IJournalRepository::class.java)
        ) { _, _, _ -> throw UnsupportedOperationException("fixture must never be invoked") }
            as IJournalRepository

        assertFailsWith<ClassCastException> {
            DataInitializer.ensureInitialized(neverCalled)
        }
        assertTrue(DataInitializer.initializedFlow.value,
            "the UI gate must open via the finally block even when init throws")
    }

    @Test
    fun resetClosesTheGateAgain() = withTempHome { _ ->
        val repo = JournalRepository()
        ensureInitialized(repo)
        assertTrue(DataInitializer.initializedFlow.value)

        DataInitializer.reset()
        assertFalse(DataInitializer.initializedFlow.value,
            "reset must re-arm the initialization flag and close the gate")
        assertFalse(DataInitializer.libraryReadyFlow.value,
            "reset must close the library gate too")

        val second = JournalRepository()
        ensureInitialized(second)
        assertTrue(second.substances.value.isNotEmpty(), "post-reset init must load the seed again")
        assertTrue(DataInitializer.initializedFlow.value)
        assertEquals(repo.substances.value.size, second.substances.value.size,
            "both inits must load the identical bundled seed")
    }

    @Test
    fun bundledSeedGetsTheMissingInteractionClasses() = withTempHome { _ ->
        val repo = JournalRepository()
        ensureInitialized(repo)
        val byName = repo.substances.value.associateBy { it.name.lowercase() }

        // desktopTest ships a 1.2 KB DoseWiki fixture, so only seed rows are guaranteed here.
        val mdma = assertNotNull(byName["mdma"], "the seed ships MDMA")
        assertTrue("serotonin_releaser" in mdma.interactionClasses,
            "MDMA must carry the serotonin releaser class, got ${mdma.interactionClasses}")

        val alprazolam = assertNotNull(byName["alprazolam"], "the seed ships Alprazolam")
        assertTrue("benzodiazepine" in alprazolam.interactionClasses,
            "Alprazolam must carry the benzodiazepine class, got ${alprazolam.interactionClasses}")

        val ayahuasca = assertNotNull(byName["ayahuasca"], "the seed ships Ayahuasca")
        assertTrue("maoi" in ayahuasca.interactionClasses,
            "Ayahuasca must carry the maoi class, got ${ayahuasca.interactionClasses}")

        val harmala = assertNotNull(byName["peganum harmala"], "the seed ships Peganum harmala")
        assertTrue("maoi" in harmala.interactionClasses,
            "Peganum harmala must carry the maoi class, got ${harmala.interactionClasses}")
    }
}
