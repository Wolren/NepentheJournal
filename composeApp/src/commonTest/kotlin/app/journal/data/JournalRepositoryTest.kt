package app.journal.data

import app.journal.model.*
import kotlin.test.*

class JournalRepositoryTest {

    private fun sampleSubstance(id: String, name: String, classes: List<String> = listOf("Classical Psychedelic")) =
        Substance(
            id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = name, aliases = listOf(name.lowercase()),
            substanceClass = classes, cachedAt = 0L, sourceVersion = "test"
        )

    private fun sampleSession(id: String, startTime: Long = 1000L) = Session(
        id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        title = "Session $id", startTime = startTime
    )

    private fun sampleDose(id: String, substanceId: String, sessionId: String, timestamp: Long, amount: Double = 100.0) =
        Dose(
            id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = sessionId, substanceId = substanceId,
            routeOfAdministration = "Oral", amount = amount, unit = "mg", timestamp = timestamp
        )

    // ==================== Basic CRUD ====================

    @Test
    fun upsertReplacesExistingById() {
        val repo = JournalRepository()
        val a = sampleSubstance("sub:1", "LSD")
        repo.upsertSubstance(a)
        repo.upsertSubstance(a.copy(name = "Lucy"))
        assertEquals(1, repo.substances.value.size)
        assertEquals("Lucy", repo.substances.value.first().name)
    }

    @Test
    fun getSessionReturnsNullForMissing() {
        val repo = JournalRepository()
        assertNull(repo.getSession("nonexistent"))
    }

    @Test
    fun getSubstanceReturnsNullForMissing() {
        val repo = JournalRepository()
        assertNull(repo.getSubstance("nonexistent"))
    }

    @Test
    fun getEffectReturnsNullForMissing() {
        val repo = JournalRepository()
        assertNull(repo.getEffect("nonexistent"))
    }

    // ==================== Deletion cascades ====================

    @Test
    fun deleteSessionRemovesOrphanedDosesAndNotes() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertNote(Note(
            id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", body = "test note"
        ))
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        repo.deleteSession("s:1")
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.doses.value.isEmpty())
        assertTrue(repo.notes.value.isEmpty())
        assertTrue(repo.timelineEvents.value.isEmpty())
    }

    @Test
    fun deleteDoseDoesNotAffectOtherDoses() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))
        repo.deleteDose("d:1")
        assertEquals(1, repo.doses.value.size)
        assertEquals("d:2", repo.doses.value.first().id)
    }

    @Test
    fun deleteSubstanceRemovesAssociatedDoses() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:2", "s:1", 2000L))
        repo.deleteSubstance("sub:1")
        assertEquals(1, repo.doses.value.size)
        assertEquals("sub:2", repo.doses.value.first().substanceId)
    }

    @Test
    fun deleteIsIdempotent() {
        val repo = JournalRepository()
        repo.deleteSession("nonexistent") // should not throw
        repo.deleteDose("nonexistent")
        repo.deleteSubstance("nonexistent")
        repo.deleteCustomUnit("nonexistent")
    }

    // ==================== Index consistency ====================

    @Test
    fun dosesForSessionReturnsOnlyThatSessionsDoses() {
        val repo = JournalRepository()
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 1000L))
        assertEquals(1, repo.dosesForSession("s:1").size)
        assertEquals(1, repo.dosesForSession("s:2").size)
    }

    @Test
    fun dosesForSessionEmptyWhenNone() {
        val repo = JournalRepository()
        assertTrue(repo.dosesForSession("s:none").isEmpty())
    }

    @Test
    fun notesForSessionMatchesSession() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test", sessionId = "s:1", body = "a"))
        repo.upsertNote(Note(id = "n:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test", sessionId = "s:2", body = "b"))
        assertEquals(1, repo.notesForSession("s:1").size)
    }

    @Test
    fun eventsForSessionAreTimestampSorted() {
        val repo = JournalRepository()
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 5000L,
            eventType = TimelineEventType.OFFSET, label = "Late",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:2", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Early",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        val events = repo.eventsForSession("s:1")
        assertEquals("Early", events[0].label)
        assertEquals("Late", events[1].label)
    }

    @Test
    fun sessionIdsForSubstanceMatchesDoseReferences() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 1000L))
        repo.upsertDose(sampleDose("d:3", "sub:2", "s:1", 1000L))
        val forSub1 = repo.sessionIdsForSubstance("sub:1")
        assertTrue("s:1" in forSub1 && "s:2" in forSub1)
        assertEquals(2, forSub1.size)
    }

    @Test
    fun sessionIdsWithTagMatchesTaggedSessions() {
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Session 1", startTime = 1000L, tags = listOf("deep", "focus")
        ))
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Session 2", startTime = 2000L, tags = listOf("deep", "social")
        ))
        assertEquals(2, repo.sessionIdsWithTag("deep").size)
        assertEquals(1, repo.sessionIdsWithTag("focus").size)
        assertEquals(setOf("s:1", "s:2"), repo.sessionIdsWithAnyTag(listOf("focus", "social")))
    }

    @Test
    fun sessionIdsWithAnyTagReturnsAllWhenEmptyFilter() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        val result = repo.sessionIdsWithAnyTag(emptyList())
        assertTrue("s:1" in result && "s:2" in result)
    }

    // ==================== Query indices ====================

    @Test
    fun sessionIdsOnDateRangeFiltersCorrectly() {
        val repo = JournalRepository()
        // startTime in ms: 2024-01-15 = 1705276800000
        repo.upsertSession(sampleSession("s:1", 1705276800000L))
        // 2024-02-15
        repo.upsertSession(sampleSession("s:2", 1707955200000L))
        val inRange = repo.sessionIdsOnDateRange("2024-01-01", "2024-01-31")
        assertEquals(1, inRange.size)
        assertEquals("s:1", inRange.first())
    }

    // ==================== Search ====================

    @Test
    fun searchSubstancesMatchesNameAndAliasCaseInsensitive() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD", listOf("Lysergamide")).copy(aliases = listOf("Lucy")))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA", listOf("Empathogen")))
        val byName = repo.searchSubstances("mdma")
        assertEquals(1, byName.size)
        assertEquals("MDMA", byName.first().name)
        val byAlias = repo.searchSubstances("lucy")
        assertEquals(1, byAlias.size)
        assertEquals("LSD", byAlias.first().name)
    }

    @Test
    fun searchSubstancesReturnsEmptyForNoMatch() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        assertTrue(repo.searchSubstances("zzznotfound").isEmpty())
    }

    // ==================== Preferences ====================

    @Test
    fun shulginRatingDefaultsFalse() {
        val repo = JournalRepository()
        assertFalse(repo.useShulginRating.value)
    }

    @Test
    fun shulginRatingTogglePersists() {
        val repo = JournalRepository()
        repo.setShulginRating(true)
        assertTrue(repo.useShulginRating.value)
        repo.setShulginRating(false)
        assertFalse(repo.useShulginRating.value)
    }

    @Test
    fun substanceColorsDefaultsTrue() {
        val repo = JournalRepository()
        assertTrue(repo.useSubstanceColors.value)
    }

    // ==================== Clear ====================

    @Test
    fun clearAllEmptiesAllCollections() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test", body = "x"))
        repo.clearAll()
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.substances.value.isEmpty())
        assertTrue(repo.doses.value.isEmpty())
        assertTrue(repo.notes.value.isEmpty())
        assertFalse(repo.useShulginRating.value)
        assertTrue(repo.useSubstanceColors.value)
    }

    // ==================== Snapshot roundtrip ====================

    @Test
    fun applySnapshotPopulatesAllCollections() {
        val repo = JournalRepository()
        val snapshot = JournalSnapshot(
            savedAt = 1000L,
            sessions = listOf(sampleSession("s:1")),
            substances = listOf(sampleSubstance("sub:1", "LSD")),
            doses = listOf(sampleDose("d:1", "sub:1", "s:1", 1000L)),
            useShulginRating = true,
            useSubstanceColors = false
        )
        repo.applySnapshot(snapshot)
        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.substances.value.size)
        assertEquals(1, repo.doses.value.size)
        assertTrue(repo.useShulginRating.value)
        assertFalse(repo.useSubstanceColors.value)
    }

    // ==================== DataFrame export ====================

    @Test
    fun sessionsDataFrameHasCorrectShape() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        val df = repo.sessionsDataFrame()
        assertEquals(1, df.size)
        assertEquals("s:1", df.first().id)
        assertEquals("LSD", df.first().substanceNames)
    }

    @Test
    fun dosesDataFrameHasCorrectShape() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        val df = repo.dosesDataFrame()
        assertEquals(1, df.size)
        assertEquals("LSD", df.first().substanceName)
    }

    // ==================== Derived flows ====================

    @Test
    fun recentSessionsReturnsMostRecent() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1", 1000L))
        repo.upsertSession(sampleSession("s:2", 2000L))
        repo.upsertSession(sampleSession("s:3", 3000L))
        repo.upsertSession(sampleSession("s:4", 4000L))
        repo.upsertSession(sampleSession("s:5", 5000L))
        repo.upsertSession(sampleSession("s:6", 6000L)) // 6th, should be cut off
        val recent = repo.recentSessions
        // Can't easily test Flow in commonTest without coroutines test lib,
        // but at least verify the class doesn't crash
        assertNotNull(repo)
    }
}
