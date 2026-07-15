package app.journal.data

import app.journal.model.*
import kotlin.test.*

class AppJsonTest {

    private fun sampleSubstance(id: String, name: String) = Substance(
        id = id, name = name, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        substanceClass = listOf("Classical Psychedelic"), cachedAt = 0L, sourceVersion = "test"
    )

    private fun sampleSession(id: String) = Session(
        id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        title = "Session $id", startTime = 1000L
    )

    private fun sampleDose(id: String, sessionId: String, substanceId: String) = Dose(
        id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        sessionId = sessionId, substanceId = substanceId,
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 1000L
    )

    @Test
    fun snapshotCapturesAllEntities() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))
        repo.setShulginRating(true)
        repo.setObsidianAutoExport(true)
        repo.setObsidianSubfolder("Subfolder")

        val snap = AppJson.snapshot(repo)

        assertEquals(1, snap.substances.size)
        assertEquals(1, snap.sessions.size)
        assertEquals(1, snap.doses.size)
        assertTrue(snap.useShulginRating)
        assertTrue(snap.obsidianAutoExport)
        assertEquals("Subfolder", snap.obsidianSubfolder)
    }

    @Test
    fun snapshotOfEmptyRepoHasDefaults() {
        val repo = JournalRepository()
        val snap = AppJson.snapshot(repo)

        assertTrue(snap.sessions.isEmpty())
        assertTrue(snap.substances.isEmpty())
        assertTrue(snap.doses.isEmpty())
        assertTrue(snap.notes.isEmpty())
        assertTrue(snap.timelineEvents.isEmpty())
        assertTrue(snap.interactions.isEmpty())
        assertTrue(snap.effects.isEmpty())
        assertTrue(snap.customUnits.isEmpty())
        assertFalse(snap.useShulginRating)
        assertTrue(snap.useSubstanceColors)
    }

    @Test
    fun applyRestoresAllEntities() {
        val snap = JournalSnapshot(
            savedAt = 1000L,
            sessions = listOf(sampleSession("s:1")),
            substances = listOf(sampleSubstance("sub:1", "LSD")),
            doses = listOf(sampleDose("d:1", "s:1", "sub:1")),
            notes = listOf(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test", body = "note")),
            timelineEvents = listOf(TimelineEvent(id = "e:1", sessionId = "s:1", timestamp = 1000L,
                eventType = TimelineEventType.ONSET, label = "Start", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test")),
            useShulginRating = true,
            useSubstanceColors = false
        )

        val repo = JournalRepository()
        AppJson.apply(repo, snap)

        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.substances.value.size)
        assertEquals(1, repo.doses.value.size)
        assertEquals(1, repo.notes.value.size)
        assertEquals(1, repo.timelineEvents.value.size)
        assertTrue(repo.useShulginRating.value)
        assertFalse(repo.useSubstanceColors.value)
    }

    @Test
    fun snapshotRoundtripPreservesData() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))
        repo.upsertDose(sampleDose("d:2", "s:1", "sub:2"))
        repo.setShulginRating(true)

        val snap = AppJson.snapshot(repo)
        val repo2 = JournalRepository()
        AppJson.apply(repo2, snap)

        assertEquals(2, repo2.substances.value.size)
        assertEquals(2, repo2.sessions.value.size)
        assertEquals(2, repo2.doses.value.size)
        assertEquals("LSD", repo2.getSubstance("sub:1")?.name)
        assertEquals("MDMA", repo2.getSubstance("sub:2")?.name)
        assertTrue(repo2.useShulginRating.value)
    }

    @Test
    fun applyDoesNotModifyRepoWhenSnapshotEmpty() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))

        val emptySnap = JournalSnapshot(savedAt = 0L)
        AppJson.apply(repo, emptySnap)

        assertEquals(1, repo.substances.value.size)
        // Empty snapshot does not clear existing data (applyBatch with empty lists is no-op)
    }

    @Test
    fun applyWithVersionMismatchDoesNotCrash() {
        val snap = JournalSnapshot(
            savedAt = 0L,
            version = 0, // old version
            sessions = listOf(sampleSession("s:1"))
        )
        val repo = JournalRepository()
        // Should not throw despite version mismatch
        AppJson.apply(repo, snap)
        assertEquals(1, repo.sessions.value.size)
    }
}
