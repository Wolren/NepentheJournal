package app.journal.data

import app.journal.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
    fun deleteIsIdempotentWithStateUnchanged() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        val sessionsBefore = repo.sessions.value.size
        val dosesBefore = repo.doses.value.size
        val substancesBefore = repo.substances.value.size

        repo.deleteSession("nonexistent")
        repo.deleteDose("nonexistent")
        repo.deleteSubstance("nonexistent")
        repo.deleteCustomUnit("nonexistent")

        assertEquals(sessionsBefore, repo.sessions.value.size)
        assertEquals(dosesBefore, repo.doses.value.size)
        assertEquals(substancesBefore, repo.substances.value.size)
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
    fun recentSessionsReturnsMostRecent() = runBlocking {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1", 1000L))
        repo.upsertSession(sampleSession("s:2", 2000L))
        repo.upsertSession(sampleSession("s:3", 3000L))
        repo.upsertSession(sampleSession("s:4", 4000L))
        repo.upsertSession(sampleSession("s:5", 5000L))
        repo.upsertSession(sampleSession("s:6", 6000L))
        val recent = repo.recentSessions.first()
        assertEquals(5, recent.size)
        assertEquals("s:6", recent[0].id)
        assertEquals("s:5", recent[1].id)
        assertEquals("s:2", recent[4].id)
    }

    // ==================== applyBatch ====================

    @Test
    fun applyBatchPopulatesAllCollections() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.applyBatch(
            sessions = listOf(sampleSession("s:1")),
            doses = listOf(sampleDose("d:1", "sub:1", "s:1", 1000L)),
            notes = listOf(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test", sessionId = "s:1", body = "n")),
            timelineEvents = listOf(TimelineEvent(id = "e:1", sessionId = "s:1", timestamp = 1000L,
                eventType = TimelineEventType.ONSET, label = "Start", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test")),
            interactions = listOf(Interaction(id = "i:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                substanceAId = "sub:1", substanceBId = "sub:2", riskLevel = InteractionRisk.UNSAFE)),
            effects = listOf(Effect(id = "ef:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                name = "Euphoria", substanceIds = listOf("sub:1"))),
            customUnits = listOf(CustomUnit(id = "u:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                substanceId = "sub:1", name = "tab"))
        )
        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.doses.value.size)
        assertEquals(1, repo.notes.value.size)
        assertEquals(1, repo.timelineEvents.value.size)
        assertEquals(1, repo.interactions.value.size)
        assertEquals(1, repo.effects.value.size)
        assertEquals(1, repo.customUnits.value.size)
    }

    @Test
    fun applyBatchBuildsCorrectIndices() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.applyBatch(
            sessions = listOf(
                sampleSession("s:1", 1000L),
                Session(id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    title = "S2", startTime = 2000L)
            ),
            doses = listOf(
                sampleDose("d:1", "sub:1", "s:1", 1000L),
                sampleDose("d:2", "sub:2", "s:1", 1500L)
            )
        )
        // Session indices
        assertEquals(2, repo.sessions.value.size)
        // Dose indices
        assertEquals(2, repo.dosesForSession("s:1").size)
        assertEquals(1, repo.sessionIdsForSubstance("sub:1").size)
        assertTrue("s:1" in repo.sessionIdsForSubstance("sub:1"))
        assertEquals(1, repo.sessionIdsForSubstance("sub:2").size)
        assertTrue("s:1" in repo.sessionIdsForSubstance("sub:2"))
    }

    @Test
    fun applyBatchEmptyListsAreNoop() {
        val repo = JournalRepository()
        repo.applyBatch() // all defaults = empty lists
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.doses.value.isEmpty())
        assertTrue(repo.substances.value.isEmpty())
    }

    @Test
    fun applyBatchLastWriterWinsSkipsOlderEntities() {
        val repo = JournalRepository()
        val newer = Session(id = "s:lww", createdAt = 1000L, updatedAt = 2000L, deviceOrigin = "test",
            title = "Newer", startTime = 1000L)
        val older = newer.copy(title = "Older", updatedAt = 1000L)

        // Blind upsert (default) overwrites; seed/restore semantics
        repo.applyBatch(sessions = listOf(newer))
        repo.applyBatch(sessions = listOf(older))
        assertEquals("Older", repo.getSession("s:lww")?.title)

        // LWW (sync path) must skip the stale entity
        repo.applyBatch(sessions = listOf(newer))
        repo.applyBatch(sessions = listOf(older), lastWriterWins = true)
        assertEquals("Newer", repo.getSession("s:lww")?.title)
    }

    @Test
    fun applyBatchLastWriterWinsAllowsEqualOrNewer() {
        val repo = JournalRepository()
        val base = Session(id = "s:eq", createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
            title = "Base", startTime = 1000L)
        repo.applyBatch(sessions = listOf(base), lastWriterWins = true)
        // Equal updatedAt must apply (idempotent re-delivery)
        repo.applyBatch(sessions = listOf(base.copy(title = "Same-ts")), lastWriterWins = true)
        assertEquals("Same-ts", repo.getSession("s:eq")?.title)
        // Newer must apply
        repo.applyBatch(sessions = listOf(base.copy(title = "New", updatedAt = 3000L)), lastWriterWins = true)
        assertEquals("New", repo.getSession("s:eq")?.title)
    }

    @Test
    fun applyBatchThenIndividualUpsertPreservesIndices() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.applyBatch(
            sessions = listOf(sampleSession("s:1")),
            doses = listOf(sampleDose("d:1", "sub:1", "s:1", 1000L))
        )
        // Add a second dose individually
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))
        assertEquals(2, repo.dosesForSession("s:1").size)
        assertEquals(1, repo.sessionIdsForSubstance("sub:1").size)
        assertTrue("s:1" in repo.sessionIdsForSubstance("sub:1"))
    }

    // ==================== substanceDoseStats ====================

    @Test
    fun substanceDoseStatsStartsEmpty() {
        val repo = JournalRepository()
        assertTrue(repo.substanceDoseStats.isEmpty())
    }

    @Test
    fun substanceDoseStatsUpdatesOnUpsertDose() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        val stats = repo.substanceDoseStats
        assertEquals(1, stats.size)
        val (count, lastUsed) = stats["sub:1"]!!
        assertEquals(1, count)
        assertEquals(1000L, lastUsed)
    }

    @Test
    fun substanceDoseStatsMultipleDosesSameSession() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))
        val stats = repo.substanceDoseStats
        val (count, lastUsed) = stats["sub:1"]!!
        assertEquals(1, count)  // distinct sessions, not total doses
        assertEquals(2000L, lastUsed)
    }

    @Test
    fun substanceDoseStatsDistinctSessionsAcrossMultipleSessions() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 2000L))
        val stats = repo.substanceDoseStats
        val (count, lastUsed) = stats["sub:1"]!!
        assertEquals(2, count)  // two distinct sessions
        assertEquals(2000L, lastUsed)
    }

    @Test
    fun substanceDoseStatsRecalculatesOnDeleteDose() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))
        repo.deleteDose("d:1")
        val stats = repo.substanceDoseStats
        val (count, lastUsed) = stats["sub:1"]!!
        assertEquals(1, count)
        assertEquals(2000L, lastUsed)
    }

    @Test
    fun substanceDoseStatsRecalculatesOnDeleteSession() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.deleteSession("s:1")
        assertTrue(repo.substanceDoseStats.isEmpty(), "dose stats should be empty after session deletion removes all doses")
    }

    @Test
    fun substanceDoseStatsMultipleSubstances() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:2", "s:1", 2000L))
        assertEquals(2, repo.substanceDoseStats.size)
    }

    // ==================== upsertNoteWithConflict ====================

    @Test
    fun upsertNoteWithConflictReturnsNullForNullSessionId() {
        val repo = JournalRepository()
        val note = Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = null, body = "no session")
        assertNull(repo.upsertNoteWithConflict(note, "remote"))
        assertTrue(repo.notes.value.isEmpty())
    }

    @Test
    fun upsertNoteWithConflictCreatesConflictSiblingOnBodyMismatch() {
        val repo = JournalRepository()
        val local = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "local version")
        repo.upsertNote(local)
        val remote = Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "remote",
            sessionId = "s:1", body = "remote version")
        val resolved = repo.upsertNoteWithConflict(remote, "remote")
        assertNotNull(resolved)
        assertEquals(1, resolved.conflictSiblings.size)
        assertEquals("remote version", resolved.conflictSiblings.first().body)
        assertEquals("remote", resolved.conflictSiblings.first().deviceOrigin)
    }

    @Test
    fun upsertNoteWithConflictNoConflictWhenBodiesMatch() {
        val repo = JournalRepository()
        val local = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "same body")
        repo.upsertNote(local)
        val remote = Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "remote",
            sessionId = "s:1", body = "same body")
        val resolved = repo.upsertNoteWithConflict(remote, "remote")
        assertNotNull(resolved)
        assertTrue(resolved.conflictSiblings.isEmpty())
    }

    @Test
    fun upsertNoteWithConflictUpdatesExistingNote() {
        val repo = JournalRepository()
        val local = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "old")
        repo.upsertNote(local)
        val remote = Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "remote",
            sessionId = "s:1", body = "new")
        repo.upsertNoteWithConflict(remote, "remote")
        // The stored note should have the remote body (last write wins) plus conflict sibling
        val stored = repo.notes.value.find { it.id == "n:1" }
        assertNotNull(stored)
        assertEquals("new", stored.body)
        assertEquals(1, stored.conflictSiblings.size)
    }

    // ==================== upsertTimelineEvent ====================

    @Test
    fun upsertTimelineEventIndexesBySession() {
        val repo = JournalRepository()
        val event = TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        )
        repo.upsertTimelineEvent(event)
        assertEquals(1, repo.eventsForSession("s:1").size)
        assertEquals("e:1", repo.eventsForSession("s:1").first().id)
    }

    @Test
    fun upsertTimelineEventUpdateReplacesInIndex() {
        val repo = JournalRepository()
        val event = TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        )
        repo.upsertTimelineEvent(event)
        // Update with same ID but different session
        val updated = event.copy(sessionId = "s:2", label = "Moved")
        repo.upsertTimelineEvent(updated)
        // Old session should not have the event
        assertTrue(repo.eventsForSession("s:1").isEmpty())
        // New session should have it
        assertEquals(1, repo.eventsForSession("s:2").size)
        assertEquals("Moved", repo.eventsForSession("s:2").first().label)
    }

    @Test
    fun upsertTimelineEventAcrossSessionsDoesNotDuplicate() {
        val repo = JournalRepository()
        val event = TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        )
        repo.upsertTimelineEvent(event)
        // Upsert same event again (same session) — should not duplicate
        repo.upsertTimelineEvent(event)
        assertEquals(1, repo.eventsForSession("s:1").size)
    }

    // ==================== deleteTimelineEvent ====================

    @Test
    fun deleteTimelineEventRemovesFromIndex() {
        val repo = JournalRepository()
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:2", sessionId = "s:1", timestamp = 2000L,
            eventType = TimelineEventType.PEAK, label = "Peak",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        assertEquals(2, repo.eventsForSession("s:1").size)
        repo.deleteTimelineEvent("e:1")
        assertEquals(1, repo.eventsForSession("s:1").size)
        assertEquals("e:2", repo.eventsForSession("s:1").first().id)
    }

    @Test
    fun deleteTimelineEventIsIdempotent() {
        val repo = JournalRepository()
        repo.upsertTimelineEvent(TimelineEvent(
            id = "e:1", sessionId = "s:1", timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"
        ))
        repo.deleteTimelineEvent("e:1")
        // Second delete should be a no-op
        repo.deleteTimelineEvent("e:1")
        assertTrue(repo.eventsForSession("s:1").isEmpty())
        assertTrue(repo.timelineEvents.value.isEmpty())
    }

    // ==================== substanceDoseStats on deleteSubstance ====================

    @Test
    fun deleteSubstanceClearsDoseStats() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        assertEquals(1, repo.substanceDoseStats.size)
        repo.deleteSubstance("sub:1")
        assertTrue(repo.substanceDoseStats.isEmpty())
    }

    @Test
    fun deleteSubstanceDoesNotAffectOtherSubstanceStats() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:2", "s:1", 2000L))
        assertEquals(2, repo.substanceDoseStats.size)
        repo.deleteSubstance("sub:1")
        assertEquals(1, repo.substanceDoseStats.size)
        assertTrue(repo.substanceDoseStats.containsKey("sub:2"))
    }

    // ==================== upsertNote index consistency ====================

    @Test
    fun upsertNoteWithNullSessionIdDoesNotIndexButStillStores() {
        val repo = JournalRepository()
        val note = Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = null, body = "orphan note")
        repo.upsertNote(note)
        // Note is stored in the store
        assertEquals(1, repo.notes.value.size)
        // But not indexed by session
        assertTrue(repo.notesForSession("any").isEmpty())
    }

    @Test
    fun upsertNoteMovingBetweenSessionsUpdatesIndex() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", body = "a"))
        assertEquals(1, repo.notesForSession("s:1").size)
        // Move to different session
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:2", body = "b"))
        assertTrue(repo.notesForSession("s:1").isEmpty())
        assertEquals(1, repo.notesForSession("s:2").size)
    }
}
