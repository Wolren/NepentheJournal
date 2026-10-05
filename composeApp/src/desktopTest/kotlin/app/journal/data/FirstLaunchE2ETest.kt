package app.journal.data

import app.journal.ingest.DoseWikiIngestor
import app.journal.model.Session
import app.journal.model.SyncConfig
import app.journal.sync.SyncTransport
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.first
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
 * First-launch / relaunch stability, end to end.
 *
 * DataInitializerTest proves the in-process guard: one ensureInitialized call
 * runs the pipeline and a second call in the SAME process is a no-op. That is
 * not the same thing as a second LAUNCH, which is what this file covers — a
 * brand-new JournalRepository reading the file the previous run left on disk.
 *
 * Four real startup scenarios:
 *
 *  1. Cold start on an empty home seeds the library and leaves a store file
 *     that decodes at the current snapshot version.
 *  2. Second launch reads from disk and takes the `libraryFresh` skip path —
 *     proven the hard way: a user edit made after launch 1 would be reverted
 *     (and a user session wiped) by `repo.applySnapshot(seed)` if re-ingest
 *     ran again. This is the "first launch must stay fast and must not eat
 *     your data" property.
 *  3. Sync-applied content survives a full app restart. This is the
 *     intersection of both goals: a peer's data must be on disk BEFORE the
 *     ack (audit D1 / persistAfterApply) and must still be there after the
 *     process is thrown away and the app starts again.
 *  4. A live timer left behind by a crashed launch is aborted on the next
 *     start instead of piling up forever.
 *
 * Every test redirects user.home into a throwaway directory (the
 * JournalStoreTest withTempHome pattern) so the real ~/.nepenthe store is
 * never touched.
 */
class FirstLaunchE2ETest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-test-launch-${System.nanoTime()}"
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

    /** Scratch dirs for the sync transports in test 3. */
    private var hostDir: File? = null
    private var clientDir: File? = null
    private var transport: SyncTransport? = null

    @BeforeTest
    fun resetBefore() {
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    @AfterTest
    fun resetAfter() {
        runCatching {
            val t = transport
            if (t != null) runBlocking { withTimeout(10_000) { t.stopHosting() } }
        }
        transport = null
        hostDir?.deleteRecursively()
        clientDir?.deleteRecursively()
        hostDir = null
        clientDir = null
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    /** Simulates the app process going away: drop all in-memory state. */
    private fun simulateRestart() {
        DataInitializer.reset()
        DoseWikiIngestor.reset()
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000); true }
    } catch (e: Exception) {
        false
    }

    private fun launchConfig(port: Int, name: String) = SyncConfig(
        id = "cfg:$name", createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(), deviceOrigin = "test",
        deviceId = "device-$name", displayName = name, listenerPort = port
    )

    // ------------------------------------------------------------------
    // 1. Cold start
    // ------------------------------------------------------------------

    @Test
    fun coldStartOnAnEmptyHomeSeedsTheLibraryAndWritesAVersionedStore() = withTempHome { home ->
        val repo = JournalRepository()
        assertFalse(File(home, ".nepenthe").exists(), "fixture: the home starts empty")

        DataInitializer.ensureInitialized(repo)

        assertTrue(DataInitializer.initializedFlow.value, "the UI gate must open")
        assertTrue(repo.substances.value.isNotEmpty(), "a cold start must load the bundled seed")
        assertNotNull(repo.seedFingerprint.value, "a successful seed load stamps the fingerprint")

        // The store file must exist and decode at the CURRENT schema version,
        // not at some stale or forward version a downgrade could not read.
        val path = JournalStore(repo).dataPath()
        val file = File(path)
        assertTrue(file.exists(), "a cold start must write $path")
        val decoded = decodeSnapshot(file.readText())
        assertTrue(decoded.isSuccess, "the written journal must parse: ${decoded.exceptionOrNull()}")
        val snapshot = decoded.getOrThrow()
        assertEquals(JournalSnapshot.CURRENT_VERSION, snapshot.version,
            "the store must be written at the current snapshot version")
        assertEquals(repo.substances.value.size, snapshot.substances.size,
            "everything the seed loaded must be on disk")
        assertTrue(snapshot.sessions.isEmpty(), "fixture: no sessions on a cold start")

        // ...and the next reader (a fresh repo) gets the same library back.
        val reread = JournalRepository()
        JournalStore(reread).load()
        assertEquals(repo.substances.value.map { it.id }.sorted(),
            reread.substances.value.map { it.id }.sorted(),
            "a plain load must reproduce the seeded library exactly")
    }

    // ------------------------------------------------------------------
    // 2. Second launch skips re-ingest and keeps user data
    // ------------------------------------------------------------------

    @Test
    fun secondLaunchReadsFromDiskAndSkipsReingestWithoutLosingUserEdits() = withTempHome { _ ->
        // ---- launch 1 ----
        val first = JournalRepository()
        DataInitializer.ensureInitialized(first)
        assertTrue(first.substances.value.isNotEmpty(), "fixture: launch 1 seeds the library")
        val seedCount = first.substances.value.size
        val fingerprint = assertNotNull(first.seedFingerprint.value, "fixture: fingerprint stamped")

        // A user edit the seed does NOT contain, plus a user session.
        val marker = "UserRenamedMarker"
        val victim = first.substances.value.first()
        first.upsertSubstance(victim.copy(name = marker, deviceOrigin = "user"))
        val stamp = System.currentTimeMillis()
        first.upsertSession(
            Session(
                id = "s:user-session", createdAt = stamp, updatedAt = stamp,
                deviceOrigin = "user", title = "Written on launch 1",
                startTime = stamp, endTime = stamp + 60_000L
            )
        )
        JournalStore(first).save()
        assertTrue(
            File(JournalStore(first).dataPath()).readText().contains(marker),
            "fixture: the marker must be on disk before the restart"
        )

        // ---- launch 2: brand-new process, brand-new repository ----
        simulateRestart()
        val second = JournalRepository()
        assertFalse(second.substances.value.any { it.name == marker },
            "fixture: launch 2 starts from disk, not from a shared repo")
        DataInitializer.ensureInitialized(second)

        assertTrue(DataInitializer.initializedFlow.value, "the gate must open on launch 2")
        assertEquals(fingerprint, second.seedFingerprint.value,
            "launch 2 must report the same seed fingerprint")
        assertEquals(seedCount, second.substances.value.size,
            "the library must keep its shape across launches")
        // THE re-ingest detector: applySnapshot() MERGES (bulkPutLocked skips
        // empty lists), so a re-ingest is visible precisely as seed rows being
        // put back over user rows by id. A reverted rename is that symptom.
        assertTrue(
            second.substances.value.any { it.name == marker },
            "second launch must SKIP re-ingest: applySnapshot(seed) puts the " +
                "seed row back over the user's rename"
        )
        // Session survival is a separate, weaker property (sessions are not in
        // the seed, so a merge leaves them alone) — it still pins that a
        // restart restores user content, but it does NOT detect re-ingest.
        assertEquals(
            "Written on launch 1",
            second.sessions.value.singleOrNull { it.id == "s:user-session" }?.title,
            "a user session from the previous run must survive a restart"
        )

        // And the skip must not be a stale-cache illusion: launch 3 agrees.
        simulateRestart()
        val third = JournalRepository()
        DataInitializer.ensureInitialized(third)
        assertTrue(third.substances.value.any { it.name == marker },
            "a third launch must still not clobber the user's edit")
        assertEquals(seedCount, third.substances.value.size, "three launches, one library")
    }

    // ------------------------------------------------------------------
    // 3. Sync-applied content survives a restart
    // ------------------------------------------------------------------

    @Test
    fun contentAppliedFromAPeerIsOnDiskAndSurvivesAFullRestart() = withTempHome { _ -> runBlocking {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        val hDir = File(base, "nepenthe-launch-host-${System.nanoTime()}").apply { mkdirs() }
        val cDir = File(base, "nepenthe-launch-client-${System.nanoTime()}").apply { mkdirs() }
        hostDir = hDir
        clientDir = cDir

        // ---- launch 1: a normal, seeded host ----
        val hostRepo = JournalRepository()
        DataInitializer.ensureInitialized(hostRepo)
        assertTrue(hostRepo.substances.value.isNotEmpty(), "fixture: host is seeded")
        val seededSessionCount = hostRepo.sessions.value.size

        // Production persistence shape (SyncEngineFactoryDesktop): every
        // accepted sync write is flushed to the journal BEFORE it is acked.
        val store = JournalStore(hostRepo)
        val hostTransport = SyncTransport(hostRepo, hDir.absolutePath) {
            store.save(fullBackup = false)
        }
        transport = hostTransport
        val info = withTimeout(60_000) { hostTransport.startHosting(launchConfig(0, "host")) }
        assertTrue(info.isSuccess, "host must start: ${info.exceptionOrNull()?.message}")
        val port = info.getOrThrow().port
        assertTrue(canConnect(port), "host must be reachable")
        val token = hostTransport.observeStatus().first().pairingToken
        assertNotNull(token, "host must publish a pairing token")

        // ---- a peer joins and pushes content the host has never seen ----
        val clientRepo = JournalRepository()
        val peerStamp = System.currentTimeMillis()
        clientRepo.upsertSession(
            Session(
                id = "s:synced-in", createdAt = peerStamp, updatedAt = peerStamp,
                deviceOrigin = "peer", title = "Arrived over sync",
                startTime = peerStamp, endTime = peerStamp + 3_600_000L
            )
        )
        val clientTransport = SyncTransport(clientRepo, cDir.absolutePath)
        val manual = clientTransport.connectManually("127.0.0.1", port, token)
        assertTrue(manual.isSuccess,
            "pairing + first cycle must succeed: ${manual.exceptionOrNull()?.message}")

        assertTrue(
            hostRepo.sessions.value.any { it.id == "s:synced-in" },
            "the peer session must be applied on the host"
        )
        val hostFile = File(store.dataPath())
        assertTrue(hostFile.exists(), "the host journal file must exist after a sync write")
        assertTrue(
            hostFile.readText().contains("\"s:synced-in\""),
            "the peer's session must already be on DISK before the ack " +
                "(persistAfterApply, audit D1) — otherwise a crash here loses " +
                "content the peer believes was accepted"
        )

        withTimeout(10_000) { hostTransport.stopHosting() }
        transport = null

        // ---- restart: throw away every in-memory object ----
        simulateRestart()
        val relaunched = JournalRepository()
        DataInitializer.ensureInitialized(relaunched)

        assertTrue(DataInitializer.initializedFlow.value, "the gate must open on relaunch")
        assertTrue(relaunched.substances.value.isNotEmpty(), "the seed library must still load")
        assertEquals(
            "Arrived over sync",
            relaunched.sessions.value.singleOrNull { it.id == "s:synced-in" }?.title,
            "sync-applied content must survive an app restart"
        )
        assertEquals(
            seededSessionCount + 1, relaunched.sessions.value.size,
            "the relaunch must restore the seeded sessions plus exactly the synced one"
        )
    } }

    // ------------------------------------------------------------------
    // 4. Crash leftovers
    // ------------------------------------------------------------------

    @Test
    fun aLiveTimerLeftByACrashedLaunchIsAbortedOnTheNextStart() = withTempHome { _ ->
        // ---- launch 1 leaves a stale, empty live session behind ----
        val first = JournalRepository()
        DataInitializer.ensureInitialized(first)
        val staleStart = System.currentTimeMillis() - 2 * 3_600_000L
        first.upsertSession(
            Session(
                id = "session:live:crashed", createdAt = staleStart, updatedAt = staleStart,
                deviceOrigin = "test", title = "Abandoned timer",
                startTime = staleStart, endTime = null
            )
        )
        JournalStore(first).save()
        // Fixture precondition: without it, "must be gone" on the next launch
        // would pass vacuously if the session had never been persisted at all.
        assertTrue(
            first.sessions.value.any { it.id == "session:live:crashed" },
            "fixture: the stale live session must exist before the restart"
        )

        // ---- launch 2 ----
        simulateRestart()
        val second = JournalRepository()
        DataInitializer.ensureInitialized(second)

        assertTrue(DataInitializer.initializedFlow.value, "the gate must open")
        assertTrue(
            second.sessions.value.none { it.id == "session:live:crashed" },
            "an empty live timer older than an hour must be aborted on the next " +
                "launch, not carried forever"
        )
        assertTrue(second.substances.value.isNotEmpty(), "the library is otherwise intact")
        // A non-stale live session must NOT be swept: an active timer has to
        // survive a normal restart.
        val fresh = System.currentTimeMillis() - 60_000L
        second.upsertSession(
            Session(
                id = "session:live:active", createdAt = fresh, updatedAt = fresh,
                deviceOrigin = "test", title = "Still running",
                startTime = fresh, endTime = null
            )
        )
        JournalStore(second).save()
        simulateRestart()
        val third = JournalRepository()
        DataInitializer.ensureInitialized(third)
        assertTrue(
            third.sessions.value.any { it.id == "session:live:active" },
            "a recent live session must survive a restart"
        )
    }
}
