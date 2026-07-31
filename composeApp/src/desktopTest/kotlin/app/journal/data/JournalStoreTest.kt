package app.journal.data

import app.journal.model.*
import kotlin.test.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.concurrent.thread

class JournalStoreTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-test-${System.nanoTime()}")
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

    @Test
    fun saveAndLoadRoundtrip() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Classical Psychedelic"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Visual"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Test Session", startTime = 1000L
        ))

        val store = JournalStore(repo)
        val path = store.dataPath()
        store.save()
        assertTrue(File(path).exists(), "Journal file should exist after save")

        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertEquals(1, repo2.substances.value.size)
        assertEquals("LSD", repo2.substances.value.first().name)
        assertEquals(1, repo2.sessions.value.size)
        assertEquals("Test Session", repo2.sessions.value.first().title)
    }

    @Test
    fun saveAndLoadMultipleEntityTypes() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "MDMA", createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Empathogen"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Euphoria"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Session", startTime = 1000L
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 120.0, unit = "mg", timestamp = 1000L
        ))
        repo.setShulginRating(true)
        repo.setObsidianAutoExport(true)

        JournalStore(repo).save()

        val repo2 = JournalRepository()
        JournalStore(repo2).load()
        assertEquals(1, repo2.substances.value.size)
        assertEquals(1, repo2.sessions.value.size)
        assertEquals(1, repo2.doses.value.size)
        assertTrue(repo2.useShulginRating.value)
        assertTrue(repo2.obsidianAutoExport.value)
    }

    @Test
    fun loadNonExistentFileIsNoOp() = withTempHome { _ ->
        val repo = JournalRepository()
        JournalStore(repo).load() // should not throw
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun saveOverwritesExistingFile() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "First", startTime = 1000L
        ))
        JournalStore(repo).save()

        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Second", startTime = 2000L
        ))
        JournalStore(repo).save()

        val repo2 = JournalRepository()
        JournalStore(repo2).load()
        assertEquals(1, repo2.sessions.value.size)
        assertEquals("Second", repo2.sessions.value.first().title)
    }

    @Test
    fun dataPathReturnsNonEmpty() = withTempHome { _ ->
        val repo = JournalRepository()
        val path = JournalStore(repo).dataPath()
        assertTrue(path.isNotBlank())
        assertTrue(path.endsWith(".json"), "dataPath should end with .json: $path")
    }

    @Test
    fun backupRotationCreatesVersionedCopies() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Save 3 times with different data to exercise rotation
        for (i in 1..3) {
            repo.upsertSession(Session(
                id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                title = "Session $i", startTime = i * 1000L
            ))
            store.save()
        }

        val bak1 = File("$path.bak.1")
        val bak2 = File("$path.bak.2")
        assertTrue(bak1.exists(), ".bak.1 should exist after 3 saves")
        assertTrue(bak2.exists(), ".bak.2 should exist after 3 saves")
        // Verify .bak is also present
        assertTrue(File("$path.bak").exists(), ".bak immediate-previous should exist after 3 saves")
        // Verify content rotation: .bak.2 holds the first save (Session 1)
        assertTrue(bak2.readText().contains("Session 1"), ".bak.2 should contain first-save data")
        // .bak.1 holds the second save (Session 2)
        assertTrue(bak1.readText().contains("Session 2"), ".bak.1 should contain second-save data")
    }

    @Test
    fun lightSaveSkipsBackupRotation() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Baseline", startTime = 1000L
        ))
        store.save()
        // Second full save so the .bak.1 chain actually exists
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Baseline 2", startTime = 1000L
        ))
        store.save()
        val bak1Before = File("$path.bak.1").readText()

        // A light save (sync persist path) must update the main file...
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Synced", startTime = 2000L
        ))
        store.save(fullBackup = false)
        assertTrue(File(path).readText().contains("Synced"), "main file must contain light-saved data")
        // ...but must NOT rotate the backup chain (that is the autosave's job)
        assertEquals(bak1Before, File("$path.bak.1").readText(), "light save must not touch .bak.1")
        // After two full saves only .bak.1 exists; a light save must not advance the chain
        assertFalse(File("$path.bak.2").exists(), "light save must not create new backup slots")
    }

    @Test
    fun lastLoadHadIssuesIsFalseOnCleanLoad() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "Caffeine", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Stimulant"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Focus"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        JournalStore(repo).save()

        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertFalse(store2.lastLoadHadIssues, "Flag should be false on clean load")
        assertTrue(store2.lastLoadIssueSummary.isEmpty(), "Summary should be empty on clean load")
    }

    @Test
    fun triggerAutoBackupCreatesTimestampedFile() = withTempHome { home ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "AutoSave Test", startTime = 1000L
        ))
        val store = JournalStore(repo)
        store.save()

        store.triggerAutoBackup()

        val autoDir = File(File(store.dataPath()).parentFile, ".auto")
        assertTrue(autoDir.isDirectory(), ".auto/ directory should exist")
        val files = autoDir.listFiles() ?: emptyArray()
        assertEquals(1, files.size, "Should have exactly one auto-backup file")
        assertTrue(files[0].name.matches(Regex("""\d{8}_\d{6}\.json""")),
            "Auto-backup file should match YYYYMMDD_HHmmss.json pattern: ${files[0].name}")
        assertTrue(files[0].length() > 0, "Auto-backup should not be empty")
        // Verify backup is a valid journal JSON (contains the session title)
        assertTrue(files[0].readText().contains("AutoSave Test"),
            "Auto-backup content should contain saved session data")
    }

    @Test
    fun versionedBackupsDontInterfereWithNormalSave() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "MDMA", createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Empathogen"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Euphoria"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        val store = JournalStore(repo)
        store.save()

        // Second save with updated data
        repo.upsertSubstance(Substance(
            id = "sub:2", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Visual"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        store.save()

        // Reload — should get both substances
        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertEquals(2, repo2.substances.value.size,
            "Both substances should survive after 2 saves with versioned backups")
        val names = repo2.substances.value.map { it.name }.sorted()
        assertEquals(listOf("LSD", "MDMA"), names,
            "Substance names should match after reload")
    }

    @Test
    fun saveWithRetrySucceedsAfterTransientFailure() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Retry Test", startTime = 1000L
        ))
        val store = JournalStore(repo)
        store.save()
        assertTrue(File(store.dataPath()).exists(), "Data file should exist after save")

        // Make the data file read-only so the next renameTo + direct write fail
        val dataFile = File(store.dataPath())
        dataFile.setWritable(false)

        // Attempt save while file is locked — retry loop should handle gracefully, no crash
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Retry Fail", startTime = 2000L
        ))
        store.save() // Must not throw

        // Make file writable again and save — should succeed
        dataFile.setWritable(true)
        store.save()

        val repo2 = JournalRepository()
        JournalStore(repo2).load()
        assertEquals(2, repo2.sessions.value.size,
            "Both sessions should persist after transient failure recovered")
    }

    @Test
    fun autoBackupRotationKeepsLast10() = withTempHome { home ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Rotation Test", startTime = 1000L
        ))
        val store = JournalStore(repo)
        store.save()

        val dataFile = File(store.dataPath())
        val autoDir = File(dataFile.parentFile, ".auto")
        autoDir.mkdirs()

        // Manually create 15 auto-backup files with different timestamps
        for (i in 1..15) {
            val ts = String.format("202607%02d_%02d0000", i / 2 + 1, (i % 2) * 30)
            File(autoDir, "$ts.json").writeText("backup-$i")
        }
        assertEquals(15, autoDir.listFiles()?.size ?: 0,
            "Should have 15 pre-existing auto-backups")

        // triggerAutoBackup creates ONE more (16 total) then rotates to 10
        store.triggerAutoBackup()

        val remaining = autoDir.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
        assertEquals(10, remaining.size,
            "Should keep exactly 10 auto-backups after rotation")
        // The newest file should be the one just created (YYYYMMDD_HHmmss.json pattern)
        assertTrue(remaining.first().name.matches(Regex("""\d{8}_\d{6}\.json""")),
            "Newest file should be the freshly created auto-backup: ${remaining.first().name}")
    }

    @Test
    fun restoreFromBackupRecoversData() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Original Data", startTime = 1000L
        ))
        val store = JournalStore(repo)
        // First save creates the file; second save creates .bak
        store.save()
        store.save()

        // Verify .bak was created
        val bakFile = File(store.dataPath() + ".bak")
        assertTrue(bakFile.exists(), ".bak should exist after save")

        // Corrupt the main data file
        val dataFile = File(store.dataPath())
        dataFile.writeText("CORRUPTED DATA {{{")
        assertTrue(dataFile.readText().contains("CORRUPTED"),
            "Main file should be corrupted for test")

        // Restore from backup
        val restored = store.restoreFromBackup()
        assertTrue(restored, "restoreFromBackup should return true")

        // Verify data was loaded correctly into repo
        assertEquals(1, repo.sessions.value.size,
            "Should have 1 session after restore")
        assertEquals("Original Data", repo.sessions.value.first().title,
            "Session title should match original data")

        // Verify main file is no longer corrupted
        assertFalse(dataFile.readText().contains("CORRUPTED"),
            "Main file should not contain corrupted data after restore")
        assertTrue(dataFile.readText().contains("Original Data"),
            "Main file should contain original data after restore")
    }

    @Test
    fun orphanTmpFileIsCleanedOnLoad() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Save once, then plant a stale tmp as if the process died mid-save
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Survivor", startTime = 1000L
        ))
        store.save()
        val tmp = File("$path.tmp")
        tmp.writeText("{stale partial json}")

        store.load()
        assertFalse(tmp.exists(), "load() must clean orphaned tmp files from crashed saves")
        assertTrue(repo.sessions.value.any { it.title == "Survivor" },
            "data from the intact main file must survive the cleanup")
    }

    @Test
    fun truncatedJsonSetsIssuesFlagAndKeepsUsableStore() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Write a file that is valid JSON at the start but cut mid-structure,
        // simulating a torn write that the atomic rename failed to prevent
        File(path).parentFile.mkdirs()
        val full = """{"schemaVersion":2,"sessions":[{"id":"s:1","title":"Torn","startTime":1000}"""
        File(path).writeText(full)

        store.load()
        assertTrue(store.lastLoadHadIssues,
            "lastLoadHadIssues should be true after loading truncated JSON")
        // The store must remain fully usable after recovery: no crash on save
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "PostRecovery", startTime = 2000L
        ))
        store.save()
        assertTrue(File(path).readText().contains("PostRecovery"),
            "store must be writable after a torn-load recovery")
    }

    @Test
    fun restoreFromBackupReturnsFalseWhenNoBackupExists() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        assertFalse(store.restoreFromBackup(), "restore with no backup should fail cleanly")
    }

    @Test
    fun saveWithIntegrityCheck() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Perform enough saves to create all backup slots (.bak, .bak.1 through .bak.5)
        for (i in 1..6) {
            repo.upsertSession(Session(
                id = "s:$i", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                title = "Session $i", startTime = i * 1000L
            ))
            store.save()
        }

        // Verify all backup files exist
        assertTrue(File("$path.bak").exists(), ".bak should exist after 6 saves")
        for (i in 1..5) {
            val bakFile = File("$path.bak.$i")
            assertTrue(bakFile.exists(), ".bak.$i should exist after 6 saves")
        }

        // Verify each backup file contains valid JSON
        val json = Json { ignoreUnknownKeys = true }
        assertNotNull(
            json.parseToJsonElement(File("$path.bak").readText()),
            ".bak must contain valid JSON"
        )
        for (i in 1..5) {
            val content = File("$path.bak.$i").readText()
            assertNotNull(
                json.parseToJsonElement(content),
                ".bak.$i must contain valid JSON"
            )
        }

        // Verify the main file is valid JSON and contains the last session
        val mainContent = File(path).readText()
        val mainJson = json.parseToJsonElement(mainContent)
        assertNotNull(mainJson, "Main journal file must contain valid JSON")
        assertTrue(mainContent.contains("Session 6"), "Main file should contain data from last save")
    }

    @Test
    fun restoreFromBackupRecoversCorruptData() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:LSD", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Classical Psychedelic"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Visual"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        val store = JournalStore(repo)
        // Save twice: first creates file, second creates .bak
        store.save()
        store.save()
        val path = store.dataPath()

        // Verify the main file contains valid data before corruption
        assertTrue(File(path).readText().contains("LSD"),
            "Main file should contain saved substance before corruption")

        // Corrupt the main journal file with garbage text
        File(path).writeText("NOT VALID JSON {{corrupted data")

        // Attempt restore from backup
        val restored = store.restoreFromBackup()
        assertTrue(restored, "restoreFromBackup should return true when .bak is available")

        // Verify data is intact after restore by loading a fresh store
        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertEquals(1, repo2.substances.value.size,
            "Substance should be recovered after restore from backup")
        assertEquals("LSD", repo2.substances.value.first().name,
            "Substance name should match after restore")
        assertFalse(store2.lastLoadHadIssues,
            "Restored data should load cleanly")
    }

    @Test
    fun lastLoadHadIssuesSetOnCorruptFile() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Write invalid JSON directly to the data file
        File(path).parentFile.mkdirs()
        File(path).writeText("{corrupt json!!!}}")

        // Load should attempt recovery and set lastLoadHadIssues
        store.load()
        assertTrue(store.lastLoadHadIssues,
            "lastLoadHadIssues should be true after loading corrupt file")
        assertTrue(store.lastLoadIssueSummary.isNotEmpty(),
            "lastLoadIssueSummary should describe the issue after loading corrupt file")
    }

    @Test
    fun concurrentSaveDoesNotCorrupt() = withTempHome { _ ->
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val path = store.dataPath()

        // Add some starting data
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Initial", startTime = 1000L
        ))
        store.save()

        // Save from 2 threads simultaneously
        val t1 = thread {
            repo.upsertSession(Session(
                id = "s:a", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                title = "Thread A", startTime = 2000L
            ))
            store.save()
        }
        val t2 = thread {
            repo.upsertSession(Session(
                id = "s:b", createdAt = 2L, updatedAt = 2L, deviceOrigin = "test",
                title = "Thread B", startTime = 3000L
            ))
            store.save()
        }
        t1.join()
        t2.join()

        // Verify the file is valid JSON after both saves complete
        val json = Json { ignoreUnknownKeys = true }
        val content = File(path).readText()
        assertNotNull(
            json.parseToJsonElement(content),
            "Journal file must contain valid JSON after concurrent saves"
        )

        // Verify data loads without error
        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertTrue(repo2.sessions.value.size >= 1,
            "At least one session should survive concurrent saves")
        // We don't check exact count since concurrent updates may race
        // But the file must not be corrupted
        assertFalse(store2.lastLoadHadIssues,
            "No load issues after concurrent saves")
    }
}
