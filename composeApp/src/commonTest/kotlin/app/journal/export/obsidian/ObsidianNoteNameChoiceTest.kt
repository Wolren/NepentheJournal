package app.journal.export.obsidian

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Vault file names are compared by the filesystem, not by Kotlin string
 * equality. Windows and macOS resolve `Note.md` and `Note.MD` to the same
 * file, so a case-only difference is not a free name.
 */
class ObsidianNoteNameChoiceTest {

    private val name = "2024-05-01-10-00-lsd-stest.md"

    private fun path(base: String) = "/vault/Nepenthe/$base"

    private val ours = "---\nid: \"s:test\"\n---\n```nepenthe\n{}\n```\n"

    @Test
    fun aForeignNoteWithAnUpperCaseExtensionKeepsItsName() {
        val chosen = chooseNoteFileName(
            fileName = name,
            existingPaths = listOf(path("2024-05-01-10-00-lsd-stest.MD")),
            readContent = { "hand written note" },
        )
        assertNotEquals(name, chosen, "a case-only variant is the same file on Windows and macOS")
        assertEquals("2024-05-01-10-00-lsd-stest (2).md", chosen)
    }

    @Test
    fun ourOwnNoteKeepsTheExistingSpelling() {
        val chosen = chooseNoteFileName(
            fileName = name,
            existingPaths = listOf(path("2024-05-01-10-00-lsd-stest.MD")),
            readContent = { ours },
        )
        assertEquals("2024-05-01-10-00-lsd-stest.MD", chosen,
            "reuse the existing spelling so one note is not forked into two files")
    }

    @Test
    fun aFreeNameIsUnchanged() {
        assertEquals(name, chooseNoteFileName(name, emptyList(), { null }))
        assertEquals(name, chooseNoteFileName(name, listOf(path("other.md")), { null }))
    }

    @Test
    fun aNumberedSiblingSkipsCaseVariants() {
        val chosen = chooseNoteFileName(
            fileName = name,
            existingPaths = listOf(
                path("2024-05-01-10-00-lsd-stest.MD"),
                path("2024-05-01-10-00-lsd-stest (2).MD"),
            ),
            readContent = { "hand written note" },
        )
        assertEquals("2024-05-01-10-00-lsd-stest (3).md", chosen)
    }
}
