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
        repo.upsertSession(Session(
            id = "existing:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Dive", startTime = 1720800000000L
        ))

        writeMdFile("Nepenthe", "session-1.md", canonicalBlock)

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(1, result.updated, "should update existing session")
        assertEquals(0, result.created)
        assertEquals(1, repo.sessions.value.size)
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

        assertEquals(1, result.errors.size,
            "exactly one path-traversal error, got: ${result.errors}")
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

    @Test
    fun canonicalBlockSurvivesCrlf() {
        val block = canonicalBlock
        val expected = block.substringAfter("```nepenthe\n").substringBefore("\n```")
        val crlf = block.replace("\n", "\r\n")

        assertEquals(expected, ObsidianNoteImporter.extractCanonicalBlock(block))
        assertEquals(expected, ObsidianNoteImporter.extractCanonicalBlock(crlf))
    }

    @Test
    fun importRejectsADoseWithANegativeAmount() {
        val repo = JournalRepository()
        writeMdFile("Nepenthe", "negative-dose.md", canonicalBlock.replace("\"amount\":100.0", "\"amount\":-5.0"))

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertTrue(result.errors.isNotEmpty(), "a negative dose amount must be rejected, got $result")
        assertTrue(repo.sessions.value.isEmpty(), "nothing may be written for a rejected block")
        assertTrue(repo.doses.value.isEmpty())
    }

    @Test
    fun importRejectsAnOverLongDoseUnit() {
        val repo = JournalRepository()
        val longUnit = "u".repeat(200)
        writeMdFile("Nepenthe", "long-unit.md", canonicalBlock.replace("\"unit\":\"ug\"", "\"unit\":\"$longUnit\""))

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertTrue(result.errors.isNotEmpty(), "an over-long unit must be rejected, got $result")
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.doses.value.isEmpty())
    }

    @Test
    fun importRejectsAFutureDoseTimestamp() {
        val repo = JournalRepository()
        val future = System.currentTimeMillis() + 30L * 86_400_000L
        writeMdFile(
            "Nepenthe",
            "future-dose.md",
            canonicalBlock.replace("\"timestamp\":1720800000000", "\"timestamp\":$future"),
        )

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertTrue(result.errors.isNotEmpty(), "a future dose timestamp must be rejected, got $result")
        assertTrue(repo.doses.value.isEmpty())
    }

    @Test
    fun reimportingANoteDoesNotTombstoneTheChildrenItRewrites() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        writeMdFile("Nepenthe", "session-1.md", canonicalBlock)
        val vaultDir = "${testDir.absolutePath}/Nepenthe"
        ObsidianNoteImporter.importFromVault(repo, vaultDir)
        assertEquals(1, repo.doses.value.size)

        val second = ObsidianNoteImporter.importFromVault(repo, vaultDir)

        assertEquals(1, second.updated, "the second pass updates the same session: $second")
        assertEquals(1, repo.doses.value.size, "the dose must be rewritten in place")
        val childTombstones = repo.exportTombstones().keys.filter {
            it.startsWith("dose:") || it.startsWith("note:") || it.startsWith("timelineEvent:")
        }
        assertTrue(
            childTombstones.isEmpty(),
            "a rewrite must not record deletes for ids it immediately re-creates: $childTombstones",
        )
    }

    @Test
    fun importUsesTheBlockSessionIdToPickBetweenIdenticalSessions() {
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:first", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Dive", startTime = 1720800000000L, outcome = "first outcome",
        ))
        repo.upsertSession(Session(
            id = "s:second", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Dive", startTime = 1720800000000L, outcome = "second outcome",
        ))
        val now = System.currentTimeMillis()
        val block = """
```nepenthe
{"session":{"id":"s:second","docType":"session","createdAt":$now,"updatedAt":$now,"deviceOrigin":"obsidian-import","title":"Deep Dive","startTime":1720800000000,"outcome":"from vault"},"doses":[],"notes":[],"timelineEvents":[],"exportedAt":$now,"version":1}
```
""".trimIndent()
        writeMdFile("Nepenthe", "second.md", block)

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertEquals(1, result.updated, "the note must update the session its block names: $result")
        assertEquals("from vault", repo.getSession("s:second")?.outcome)
        assertEquals("first outcome", repo.getSession("s:first")?.outcome,
            "a same-time same-title sibling must keep its own content")
    }

    @Test
    fun importRejectsAnOverLongSessionTitle() {
        val repo = JournalRepository()
        val longTitle = "T".repeat(501)
        writeMdFile(
            "Nepenthe",
            "long-title.md",
            canonicalBlock.replace("\"title\":\"Deep Dive\"", "\"title\":\"$longTitle\""),
        )

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertTrue(result.errors.isNotEmpty(), "an over-long session title must be rejected, got $result")
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun importRejectsAnOverLongSessionField() {
        val repo = JournalRepository()
        val longIntention = "i".repeat(70_000)
        writeMdFile(
            "Nepenthe",
            "long-field.md",
            canonicalBlock.replace(
                "\"startTime\":1720800000000",
                "\"startTime\":1720800000000,\"intention\":\"$longIntention\"",
            ),
        )

        val result = ObsidianNoteImporter.importFromVault(repo, "${testDir.absolutePath}/Nepenthe")

        assertTrue(result.errors.isNotEmpty(), "an over-long session field must be rejected, got $result")
        assertTrue(repo.sessions.value.isEmpty())
    }
}
