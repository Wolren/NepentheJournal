package app.journal.data.repository

import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Note
import app.journal.model.Session
import app.journal.sync.mergeSessionConflict
import app.journal.util.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3 field registers: a local write stamps only what changed, and a merge joins
 * two rows field by field. Both rows must carry a row clock; a pre-v10 side keeps the
 * whole-row updatedAt rule.
 */
class FieldRegistersTest {

    private val base = 1_700_000_000_000L

    private fun session(
        clock: String = Hlc.of(base, 0).encoded,
        title: String = "title",
        outcome: String? = null,
        fieldClocks: Map<String, String> = emptyMap(),
    ) = Session(
        id = "s:1", createdAt = base, updatedAt = base, deviceOrigin = "a", clock = clock,
        fieldClocks = fieldClocks, title = title, startTime = base, outcome = outcome,
    )

    private fun hlc(stamp: String): Hlc = requireNotNull(Hlc.parseOrNull(stamp))

    @Test
    fun aLocalWriteStampsEveryFieldWithTheRowClock() {
        val repo = JournalRepository()
        repo.upsertSession(session())
        val stored = repo.getSession("s:1")!!
        assertTrue(stored.fieldClocks.isNotEmpty())
        assertEquals(stored.clock, stored.fieldClocks["title"])
        assertEquals(stored.clock, stored.fieldClocks["outcome"])
        assertFalse(stored.fieldClocks.containsKey("id"))
        assertFalse(stored.fieldClocks.containsKey("updatedAt"))
        assertFalse(stored.fieldClocks.containsKey("clock"))
    }

    @Test
    fun editingOneFieldLeavesOtherFieldClocksAlone() {
        val repo = JournalRepository()
        repo.upsertSession(session())
        val first = repo.getSession("s:1")!!
        repo.upsertSession(first.copy(title = "renamed", updatedAt = first.updatedAt + 1))
        val second = repo.getSession("s:1")!!

        assertTrue(hlc(second.fieldClocks.getValue("title")) > hlc(first.fieldClocks.getValue("title")))
        assertEquals(first.fieldClocks["outcome"], second.fieldClocks["outcome"],
            "an untouched field keeps its clock")
        assertEquals(second.clock, second.fieldClocks["title"], "a changed field takes the write clock")
    }

    @Test
    fun disjointFieldEditsBothSurvive() {
        val left = session(
            title = "left title",
            fieldClocks = mapOf("title" to Hlc.of(base + 100L, 0).encoded),
        )
        val right = session(
            outcome = "right outcome",
            fieldClocks = mapOf("outcome" to Hlc.of(base + 50L, 0).encoded),
        )

        val merged = FieldRegisters.merge(left, right, Session.serializer())

        assertEquals("left title", merged.title)
        assertEquals("right outcome", merged.outcome)
        assertEquals(Hlc.of(base + 100L, 0).encoded, merged.fieldClocks["title"])
        assertEquals(Hlc.of(base + 50L, 0).encoded, merged.fieldClocks["outcome"])
    }

    @Test
    fun aNewerFieldClockWinsTheSameField() {
        val left = session(
            title = "left title",
            fieldClocks = mapOf("title" to Hlc.of(base + 100L, 0).encoded),
        )
        val right = session(
            title = "right title",
            fieldClocks = mapOf("title" to Hlc.of(base + 200L, 0).encoded),
        )

        assertEquals("right title", FieldRegisters.merge(left, right, Session.serializer()).title)
        assertEquals("right title", FieldRegisters.merge(right, left, Session.serializer()).title)
    }

    @Test
    fun mergeIsCommutativeAndIdempotent() {
        val left = session(
            title = "left title",
            fieldClocks = mapOf("title" to Hlc.of(base + 100L, 0).encoded),
        )
        val right = session(
            outcome = "right outcome",
            fieldClocks = mapOf("outcome" to Hlc.of(base + 50L, 0).encoded),
        )

        val merged = FieldRegisters.merge(left, right, Session.serializer())
        assertEquals(merged, FieldRegisters.merge(right, left, Session.serializer()))
        assertEquals(merged, FieldRegisters.merge(merged, left, Session.serializer()))
        assertEquals(merged, FieldRegisters.merge(merged, right, Session.serializer()))
    }

    @Test
    fun aPreV10SideKeepsWholeRowUpdatedAt() {
        val local = session(clock = Hlc.of(base, 0).encoded, title = "local title")
        val oldPeer = session(clock = "", title = "remote title").copy(updatedAt = base + 1_000L)

        assertEquals("remote title", FieldRegisters.merge(local, oldPeer, Session.serializer()).title,
            "a blank row clock falls back to updatedAt for the whole row")
        assertEquals("remote title", FieldRegisters.merge(oldPeer, local, Session.serializer()).title,
            "the fallback is symmetric: updatedAt decides both directions")
    }

    @Test
    fun aTitleOnlyRemoteEditDoesNotRaiseAnOutcomeConflict() {
        val repo = JournalRepository()
        val local = session(
            clock = Hlc.of(base, 0).encoded,
            title = "local title",
            outcome = "local outcome",
            fieldClocks = mapOf(
                "title" to Hlc.of(base, 0).encoded,
                "outcome" to Hlc.of(base + 100L, 0).encoded,
            ),
        )
        repo.applyBatch(sessions = listOf(local), lastWriterWins = false)
        val remote = local.copy(
            title = "remote title",
            updatedAt = base + 1_000L,
            clock = Hlc.of(base + 1_000L, 0).encoded,
            fieldClocks = mapOf(
                "title" to Hlc.of(base + 1_000L, 0).encoded,
                "outcome" to Hlc.of(base, 0).encoded,
            ),
        )

        val conflicts = mergeSessionConflict(repo, listOf(remote), emptyList(), "peer")

        assertEquals(0, conflicts, "a stale outcome copied with a title edit is not a conflict")
        val stored = repo.getSession("s:1")!!
        assertEquals("remote title", stored.title)
        assertEquals("local outcome", stored.outcome, "the newer local outcome survives")
        assertTrue(repo.notes.value.none { it.id.startsWith("conflict:") })
    }

    @Test
    fun aNoteBodyConflictKeepsTheLoserAsSibling() {
        val repo = JournalRepository()
        val now = System.currentTimeMillis()
        repo.upsertNote(Note(
            id = "n:1", createdAt = now, updatedAt = now, deviceOrigin = "local",
            sessionId = "s:1", title = "Local title", body = "local body",
        ))
        val stored = repo.notes.value.single()
        val incoming = stored.copy(
            clock = Hlc.of(now + 5_000L, 0).encoded,
            body = "remote body",
            title = "Remote title",
            fieldClocks = (stored.fieldClocks - "body" - "title") +
                ("body" to Hlc.of(now - 5_000L, 0).encoded),
        )

        val resolved = repo.upsertNoteWithConflict(incoming, "peer")!!

        assertEquals("local body", resolved.body, "the newer body clock keeps the local body")
        assertEquals("Remote title", resolved.title, "the title merges independently")
        val sibling = resolved.conflictSiblings.single()
        assertEquals("remote body", sibling.body)
        assertEquals("peer", sibling.deviceOrigin)
    }

    @Test
    fun aDoseMergesAmountAndNotesIndependently() {
        val repo = JournalRepository()
        val local = Dose(
            id = "d:1", createdAt = base, updatedAt = base, deviceOrigin = "a",
            clock = Hlc.of(base, 0).encoded,
            fieldClocks = mapOf(
                "amount" to Hlc.of(base, 0).encoded,
                "notes" to Hlc.of(base + 1L, 0).encoded,
            ),
            sessionId = "s:1", substanceId = "sub:1", routeOfAdministration = "Oral",
            amount = 10.0, unit = "mg", timestamp = base, notes = "local note",
        )
        val remote = local.copy(
            clock = Hlc.of(base + 10L, 0).encoded,
            updatedAt = base + 10L,
            amount = 50.0,
            notes = "remote note",
            fieldClocks = mapOf(
                "amount" to Hlc.of(base + 10L, 0).encoded,
                "notes" to Hlc.of(base, 0).encoded,
            ),
        )

        repo.applyBatch(doses = listOf(local), lastWriterWins = true)
        repo.applyBatch(doses = listOf(remote), lastWriterWins = true)

        val stored = repo.doses.value.single()
        assertEquals(50.0, stored.amount, "the newer amount clock wins its field")
        assertEquals("local note", stored.notes, "the older notes clock loses only that field")
    }
}
