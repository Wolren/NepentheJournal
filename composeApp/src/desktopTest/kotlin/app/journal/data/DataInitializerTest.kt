package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import java.io.File
import java.lang.reflect.Proxy
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

        DataInitializer.ensureInitialized(repo)
        assertTrue(repo.substances.value.isNotEmpty(), "first call must load the bundled seed")
        assertNotNull(repo.seedFingerprint.value,
            "a successful seed load stamps the fingerprint")
        assertTrue(DataInitializer.initializedFlow.value,
            "initializedFlow must be true after a completed init")

        // Probe: clear the library. If the second call re-ran initialization
        // it would repopulate the seed (empty library can never be "fresh").
        repo.clearAll()
        assertTrue(repo.substances.value.isEmpty(), "fixture check")

        DataInitializer.ensureInitialized(repo)
        assertTrue(repo.substances.value.isEmpty(),
            "second ensureInitialized must be a no-op (a re-run repopulates the seed)")
        assertTrue(DataInitializer.initializedFlow.value, "gate stays open")
    }

    @Test
    fun gateOpensEvenWhenStoreLoadFailsOnCorruptFile() = withTempHome { home ->
        val dataFile = File(home, ".nepenthe${File.separator}journal-data.json")
        dataFile.parentFile.mkdirs()
        dataFile.writeText("{ definitely not json")

        // Prove the fixture is what we think: the store reports a load issue.
        val probe = JournalStore(JournalRepository())
        probe.load()
        assertTrue(probe.lastLoadHadIssues,
            "corrupt journal file must trip the store's load-recovery path")

        // DataInitializer runs against the same corrupt file and must finish
        // with the gate open (its finally block), seed intact.
        val repo = JournalRepository()
        DataInitializer.ensureInitialized(repo)
        assertTrue(DataInitializer.initializedFlow.value,
            "initializedFlow must open even when store.load() fails")
        assertTrue(repo.substances.value.isNotEmpty(), "seed still loads")

        // Store must be usable again after init (init rewrites the file).
        JournalStore(repo).save()
        assertTrue(dataFile.readText().contains("substances"),
            "init + save must leave a valid journal file")
    }

    @Test
    fun gateOpensEvenWhenInitializationThrows() = withTempHome { _ ->
        assertFalse(DataInitializer.initializedFlow.value, "reset must close the gate first")

        // Any non-JournalRepository IJournalRepository blows up the
        // `repo as JournalRepository` cast inside runInitialization before a
        // single repository method is invoked; the proxy guarantees that.
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
        DataInitializer.ensureInitialized(repo)
        assertTrue(DataInitializer.initializedFlow.value)

        DataInitializer.reset()
        assertFalse(DataInitializer.initializedFlow.value,
            "reset must re-arm the initialization flag and close the gate")

        // And a fresh ensure runs the full pipeline again: new repo, seed loads.
        val second = JournalRepository()
        DataInitializer.ensureInitialized(second)
        assertTrue(second.substances.value.isNotEmpty(), "post-reset init must load the seed again")
        assertTrue(DataInitializer.initializedFlow.value)
        // Sanity: equality with the first run's seed count (seed is deterministic).
        assertEquals(repo.substances.value.size, second.substances.value.size,
            "both inits must load the identical bundled seed")
    }
}
