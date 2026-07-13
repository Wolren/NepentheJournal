package app.journal.export.obsidian

import app.journal.model.*
import kotlin.test.*

class ObsidianNoteRendererTest {

    private val substanceResolver: (String) -> String? = { id ->
        when (id) {
            "cid:5761" -> "LSD"
            "cid:2381" -> "Cannabis"
            else -> null
        }
    }

    private fun sampleSession() = Session(
        id = "s:test-123",
        createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        title = "LSD Exploration",
        startTime = 1720728000000L,
        endTime = 1720742400000L,
        tags = listOf("psychedelic", "introspection"),
        set = "Calm and curious",
        setting = "Home, dim lights",
        intention = "Introspection",
        outcome = "Meaningful insights about my patterns",
        rating = 8,
        isFavorite = true
    )

    @Test
    fun rendersFullSession() {
        val note = renderSessionToObsidianNote(
            session = sampleSession(),
            doses = listOf(
                Dose(
                    id = "d:1", createdAt = 1000L, updatedAt = 1000L,
                    deviceOrigin = "test", sessionId = "s:test-123",
                    substanceId = "cid:5761", routeOfAdministration = "Oral",
                    amount = 100.0, unit = "ug", timestamp = 1720728000000L,
                    stomachFullness = StomachFullness.EMPTY
                )
            ),
            notes = listOf(
                Note(
                    id = "n:1", createdAt = 1000L, updatedAt = 1000L,
                    deviceOrigin = "test", sessionId = "s:test-123",
                    title = "Pre-flight thoughts", body = "Feeling ready for this."
                )
            ),
            timelineEvents = listOf(
                TimelineEvent(
                    id = "e:1", createdAt = 1000L, updatedAt = 1000L,
                    deviceOrigin = "test", sessionId = "s:test-123",
                    timestamp = 1720728000000L,
                    eventType = TimelineEventType.ONSET,
                    label = "First effects",
                    body = "Slight shimmer in peripheral vision"
                )
            ),
            substanceNameResolver = substanceResolver
        )

        // Frontmatter
        assertTrue(note.content.startsWith("---"), "Should start with YAML frontmatter")
        assertTrue(note.content.contains("id: \"s:test-123\""), "Should contain session id")
        assertTrue(note.content.contains("title: \"LSD Exploration\""), "Should contain title")
        assertTrue(note.content.contains("rating: 8"), "Should contain rating")
        assertTrue(note.content.contains("substances:"), "Should have substances section")
        assertTrue(note.content.contains("  - \"LSD\""), "Should list LSD as substance")
        assertTrue(note.content.contains("\"psychedelic\""), "Should have psychedelic tag")
        assertTrue(note.content.contains("\"favorite\""), "Should have favorite tag")

        // Body structure
        assertTrue(note.content.contains("# LSD Exploration"), "Should have title heading")
        assertTrue(note.content.contains("## Set & Setting"), "Should have set/setting section")
        assertTrue(note.content.contains("## Doses"), "Should have doses section")
        assertTrue(note.content.contains("[[LSD]]"), "Should have substance wikilink")
        assertTrue(note.content.contains("100 ug"), "Should show dose amount")
        assertTrue(note.content.contains("Empty"), "Should show stomach fullness")
        assertTrue(note.content.contains("## Timeline"), "Should have timeline section")
        assertTrue(note.content.contains("**First effects**"), "Should have bold timeline label")
        assertTrue(note.content.contains("## Notes"), "Should have notes section")
        assertTrue(note.content.contains("### Pre-flight thoughts"), "Should have note title")
        assertTrue(note.content.contains("Feeling ready for this."), "Should have note body")
        assertTrue(note.content.contains("## Outcome"), "Should have outcome section")
        assertTrue(note.content.contains("Meaningful insights"), "Should have outcome text")

        // Canonical block
        assertTrue(note.content.contains(BLOCK_OPEN), "Should contain canonical block open marker")
        assertTrue(note.content.contains(BLOCK_CLOSE), "Should contain canonical block close marker")
        assertTrue(note.content.contains("\"version\": 1"), "Canonical block should have version")

        // Filename
        assertTrue(note.fileName.endsWith(".md"), "Filename should end with .md")
        assertTrue(note.fileName.contains("lsd"), "Filename should contain slug 'lsd'")
        assertEquals("s:test-123", note.sessionId, "Should carry session ID")
    }

    @Test
    fun rendersMinimalSession() {
        val session = Session(
            id = "s:minimal",
            createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
            title = "", startTime = 1720728000000L
        )
        val note = renderSessionToObsidianNote(
            session = session,
            doses = emptyList(),
            notes = emptyList(),
            timelineEvents = emptyList(),
            substanceNameResolver = { null }
        )

        assertTrue(note.fileName.startsWith("2024"), "Filename should start with date")
        assertTrue(note.fileName.contains("untitled"), "Filename should contain 'untitled'")
        assertFalse(note.content.contains("## Doses"), "Should not have doses section")
        assertFalse(note.content.contains("## Timeline"), "Should not have timeline section")
        assertFalse(note.content.contains("## Notes"), "Should not have notes section")
        assertFalse(note.content.contains("## Set & Setting"), "Should not have set/setting section")
        assertTrue(note.content.contains(BLOCK_OPEN), "Minimal session should still have canonical block")
    }

    @Test
    fun slugifyTests() {
        assertEquals("lsd-exploration", slugify("LSD Exploration!"))
        assertEquals("a-wild-trip-2026", slugify("A Wild/Trip: 2026!"))
        assertEquals("untitled", slugify(""))
        assertEquals("hello-world", slugify("  hello world  "))
        assertEquals("a-b-c", slugify("a!!!b...c"))
    }

    @Test
    fun sanitizeIdTests() {
        assertTrue(sanitizeIdForFilename("a".repeat(100)).length <= 16)
        assertEquals("session", sanitizeIdForFilename("!!!"))
    }

    @Test
    fun yamlEscapeTests() {
        assertEquals("plain", yamlEscape("plain"))
        assertEquals("with \\\"quotes\\\"", yamlEscape("with \"quotes\""))
        assertEquals("with \\\\backslash", yamlEscape("with \\backslash"))
        assertEquals("", yamlEscape(null))
    }

    @Test
    fun mdCellTests() {
        assertEquals("plain", mdCell("plain"))
        assertEquals("bar \\| baz", mdCell("bar | baz"))
        assertEquals("hello world", mdCell("hello\nworld"))
        assertEquals("", mdCell(null))
    }

    @Test
    fun formatDurationTests() {
        val start = 1000L
        assertEquals("2h 30m", formatDuration(start, start + 9_000_000))
        assertEquals("1h", formatDuration(start, start + 3_600_000))
        assertEquals("30m", formatDuration(start, start + 1_800_000))
        assertEquals("<1m", formatDuration(start, start + 30_000))
        assertEquals(null, formatDuration(start, null))
    }

    @Test
    fun formatAmountTests() {
        assertEquals("100 ug", formatAmount(100.0, "ug"))
        assertEquals("150.5 mg", formatAmount(150.5, "mg"))
    }

    @Test
    fun noTagsInFrontmatterWhenEmpty() {
        val session = Session(
            id = "s:no-tags", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", title = "Plain", startTime = 1000L
        )
        val note = renderSessionToObsidianNote(
            session = session, doses = emptyList(),
            notes = emptyList(), timelineEvents = emptyList(),
            substanceNameResolver = { null }
        )
        assertTrue(note.content.contains("source: \"Nepenthe Journal\""), "Should have source")
        assertFalse(note.content.contains("tags: []"), "Should not have empty tags")
    }

    @Test
    fun canonicalBlockRoundTrips() {
        val session = sampleSession()
        val doses = listOf(
            Dose(
                id = "d:rt1", createdAt = 1000L, updatedAt = 1000L,
                deviceOrigin = "test", sessionId = "s:test-123",
                substanceId = "cid:5761", routeOfAdministration = "Oral",
                amount = 100.0, unit = "ug", timestamp = 1720728000000L
            )
        )
        val note = renderSessionToObsidianNote(
            session = session, doses = doses,
            notes = emptyList(), timelineEvents = emptyList(),
            substanceNameResolver = substanceResolver
        )

        val json = ObsidianNoteImporter.extractCanonicalBlock(note.content)
        assertNotNull(json, "Should extract canonical block")

        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<ObsidianCanonicalBlock>(json!!)

        assertEquals("s:test-123", parsed.session.id)
        assertEquals("LSD Exploration", parsed.session.title)
        assertEquals(1, parsed.doses.size)
        assertEquals("cid:5761", parsed.doses[0].substanceId)
        assertEquals(100.0, parsed.doses[0].amount)
    }
}
