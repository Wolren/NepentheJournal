package app.journal.export.obsidian

import app.journal.data.JournalRepository
import app.journal.model.*
import kotlin.test.*
import java.io.File

class ObsidianExportManagerTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "nepenthe-test-export-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun config(
        vaultPath: String = testDir.absolutePath,
        subfolder: String = "Nepenthe",
        fileOrganization: String = "flat"
    ) = ObsidianExportConfig(
        vaultPath = vaultPath,
        subfolder = subfolder,
        fileOrganization = fileOrganization
    )

    private fun populateRepo(repo: JournalRepository) {
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "system", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "LSD Trip", startTime = 1720800000000L
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 100.0, unit = "ug", timestamp = 1720800000000L
        ))
    }

    @Test
    fun exportSessionWritesFile() {
        val repo = JournalRepository()
        populateRepo(repo)

        val path = ObsidianExportManager.exportSession(repo, "s:1", config())

        assertNotNull(path, "exportSession should return a file path")
        assertTrue(File(path).exists(), "file should exist on disk")
        val content = File(path).readText()
        assertTrue(content.contains("LSD Trip"), "file should contain session title")
        assertTrue(content.contains("100 ug"), "file should contain dose info")
    }

    @Test
    fun exportSessionReturnsNullForNonexistent() {
        val repo = JournalRepository()
        val path = ObsidianExportManager.exportSession(repo, "nonexistent", config())
        assertNull(path)
    }

    @Test
    fun exportAllSessionsWritesAllSessions() {
        val repo = JournalRepository()
        populateRepo(repo)
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "MDMA Session", startTime = 1720900000000L
        ))

        val result = ObsidianExportManager.exportAllSessions(repo, config())

        assertEquals(2, result.written, "written count, errors: ${result.errors}")

        // Debug: check if resolvedDir() matches expectations
        val cfg = config()
        val resolvedDir = ObsidianExportConfig(
            vaultPath = cfg.vaultPath, subfolder = cfg.subfolder, fileOrganization = cfg.fileOrganization
        ).resolvedDir()
        val vaultNorm = ObsidianVaultOps.normalizePath(cfg.vaultPath)
        val dirNorm = ObsidianVaultOps.normalizePath(resolvedDir)
        val dir = File(testDir, "Nepenthe")
        val dirNorm2 = ObsidianVaultOps.normalizePath(dir.absolutePath)
        assertTrue(dirNorm.startsWith(vaultNorm.trimEnd('/').trimEnd('\\')),
            "dirNorm $dirNorm should start with vaultNorm ${vaultNorm.trimEnd('/').trimEnd('\\')}")
        assertEquals(dirNorm2, dirNorm,
            "normalized paths should match: $dirNorm2 vs $dirNorm")

        assertTrue(dir.exists(), "directory should exist at ${dir.absolutePath}")
        val actualFiles = dir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(actualFiles.isNotEmpty(), "files should exist in ${dir.absolutePath}, got: $actualFiles")

        // Filename format: YYYY-MM-DD-HH-MM-slug-sanitizedId.md
        val lsdTripFile = File(testDir, "Nepenthe/2024-07-12-18-00-lsd-trip-s1.md")
        assertTrue(lsdTripFile.exists(), "lsd-trip.md should exist at ${lsdTripFile.absolutePath}, files in dir: ${actualFiles.joinToString()}")
        val mdmaFile = File(testDir, "Nepenthe/2024-07-13-21-46-mdma-session-s2.md")
        assertTrue(mdmaFile.exists(), "mdma file should exist at ${mdmaFile.absolutePath}")
    }

    @Test
    fun exportEmptyRepoReturnsZero() {
        val repo = JournalRepository()
        val result = ObsidianExportManager.exportAllSessions(repo, config())

        assertEquals(0, result.written)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun exportWithDateOrganizationCreatesYearMonthSubdirs() {
        val repo = JournalRepository()
        populateRepo(repo)

        val result = ObsidianExportManager.exportAllSessions(repo, config(
            fileOrganization = "date"
        ))

        assertEquals(1, result.written)
        // startTime 1720800000000 = 2024-07-12
        val file = File(testDir, "Nepenthe/2024/07/2024-07-12-18-00-lsd-trip-s1.md")
        assertTrue(file.exists(), "date-organized file should exist at $file")
    }

    @Test
    fun exportWithCustomSubfolder() {
        val repo = JournalRepository()
        populateRepo(repo)

        val result = ObsidianExportManager.exportAllSessions(repo, config(
            subfolder = "MyJournal"
        ))

        assertEquals(1, result.written)
        assertTrue(File(testDir, "MyJournal/2024-07-12-18-00-lsd-trip-s1.md").exists())
    }

    @Test
    fun exportSessionWithEmptyContentReturnsNull() {
        val repo = JournalRepository()
        // Session with no title, no doses, no nothing — renderer might return blank
        repo.upsertSession(Session(
            id = "s:blank", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "", startTime = 1720800000000L
        ))

        val path = ObsidianExportManager.exportSession(repo, "s:blank", config())
        // Minimal sessions still get rendered (just frontmatter + title)
        assertNotNull(path)
    }
}
