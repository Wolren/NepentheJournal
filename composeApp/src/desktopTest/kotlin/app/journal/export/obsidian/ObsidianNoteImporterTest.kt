package app.journal.export.obsidian

import app.journal.data.JournalRepository
import app.journal.model.*
import kotlin.test.*
import java.io.File

class ObsidianNoteImporterTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "nepenthe-test-importer-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun writeMdFile(subfolder: String, fileName: String, content: String) {
        val dir = File(testDir, subfolder)
        dir.mkdirs()
        File(dir, fileName).writeText(content)
    }

    private val canonicalBlock: String
        get() {
            val now = System.currentTimeMillis()
            return "```nepenthe\n" +
                """{"session":{"id":"s:f81d4fae","docType":"session","createdAt":$now,"updatedAt":$now,"deviceOrigin":"obsidian-import","title":"Deep Dive","startTime":1720800000000,"endTime":1720810000000,"tags":["psychedelic"]},"doses":[{"id":"d:f81d4fae","docType":"dose","createdAt":$now,"updatedAt":$now,"deviceOrigin":"obsidian-import","sessionId":"s:f81d4fae","substanceId":"sub:1","routeOfAdministration":"Oral","amount":100.0,"unit":"ug","timestamp":1720800000000}],"notes":[],"timelineEvents":[],"exportedAt":$now,"version":1}""" +
                "\n```\n"
        }

    @Test
    fun importCreatesSessionFromCanonicalBlock() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))

        writeMdFile("Nepenthe", "session-1.md", canonicalBlock)

        val vaultDir = "${testDir.absolutePath}/Nepenthe"
        val file = File(vaultDir, "session-1.md")
        assertTrue(file.exists(), "test file should exist at ${file.absolutePath}")
        val content = file.readText()
        assertTrue(content.contains("```nepenthe"), "file should contain canonical block marker")

        // Debug: check what listMdFiles returns
        val listedPaths = ObsidianVaultOps.listMdFiles(vaultDir)
        assertTrue(listedPaths.isNotEmpty(), "listMdFiles should find .md files in $vaultDir, got: $listedPaths")
        val firstPath = listedPaths.first()
        assertTrue(firstPath.isNotEmpty(), "listed path should not be empty")
        val listedContent = ObsidianVaultOps.readFile(firstPath)
        assertTrue(listedContent.contains("```nepenthe"), "file read via vaultOps should contain block marker")

        val result = ObsidianNoteImporter.importFromVault(repo, vaultDir)

        assertEquals(1, result.created, "should create 1 session, result: $result, errors: ${result.errors}")
        assertEquals(0, result.updated)
        assertEquals(1, repo.sessions.value.size)
        assertEquals("Deep Dive", repo.sessions.value.first().title)
        assertEquals(1, repo.doses.value.size)
        assertEquals("sub:1", repo.doses.value.first().substanceId)
    }

    @Test
    fun importUpdatesExistingSessionByStartTimeAndTitle() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        // Pre-existing session with same startTime and title
        repo.upsertSession(Session(
            id = "existing:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Dive", startTime = 1720800000000L
        ))

        writeMdFile("Nepenthe", "session-1.md", canonicalBlock)

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(1, result.updated, "should update existing session")
        assertEquals(0, result.created)
        assertEquals(1, repo.sessions.value.size)
        // Verify the updated session kept its original ID
        assertEquals("existing:1", repo.sessions.value.first().id)
    }

    @Test
    fun importSkipsFileWithoutCanonicalBlock() {
        val repo = JournalRepository()
        writeMdFile("Nepenthe", "handwritten-note.md", "# My Handwritten Note\n\nJust some thoughts.\n")

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(1, result.skipped)
        assertEquals(0, result.created)
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun importRejectsPathTraversal() {
        val repo = JournalRepository()
        val result = ObsidianNoteImporter.importFromVault(repo, "../etc/passwd")

        assertTrue(result.errors.isNotEmpty(), "should reject path traversal")
        assertTrue(result.errors.first().contains(".."), "error should mention path traversal")
        assertEquals(0, result.created)
    }

    @Test
    fun importHandlesMalformedCanonicalBlock() {
        val repo = JournalRepository()
        writeMdFile("Nepenthe", "corrupt.md", """
```nepenthe
this is not json
```
""".trimIndent())

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(1, result.skipped)
        assertEquals(0, result.created)
    }

    @Test
    fun importEmptyDirectoryIsNoop() {
        val repo = JournalRepository()
        val emptyDir = File(testDir, "EmptyVault")
        emptyDir.mkdirs()

        val result = ObsidianNoteImporter.importFromVault(repo, emptyDir.absolutePath)

        assertEquals(0, result.created)
        assertEquals(0, result.skipped)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun importWithMultipleSessionsProcessesAll() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))

        writeMdFile("Nepenthe", "session-1.md", canonicalBlock)

        val now = System.currentTimeMillis()
        val block2 = """
```nepenthe
{"session":{"id":"s:abc123","docType":"session","createdAt":${now},"updatedAt":${now},"deviceOrigin":"obsidian-import","title":"Second Session","startTime":1720900000000},"doses":[],"notes":[],"timelineEvents":[],"exportedAt":${now},"version":1}
```
""".trimIndent()
        writeMdFile("Nepenthe", "session-2.md", block2)

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(2, result.created)
        assertEquals(2, repo.sessions.value.size)
    }
}
