package app.journal.data

import app.journal.model.*
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

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

    // ==================== Preferences ====================

    @Test
    fun ratingScaleDefaultsOff() {
        val repo = JournalRepository()
        assertEquals(RatingScaleMode.OFF, repo.ratingScaleMode.value)
    }

    @Test
    fun ratingScaleModePersists() {
        val repo = JournalRepository()
        repo.setRatingScaleMode(RatingScaleMode.SHULGIN)
        assertEquals(RatingScaleMode.SHULGIN, repo.ratingScaleMode.value)
        repo.setRatingScaleMode(RatingScaleMode.NUMERIC)
        assertEquals(RatingScaleMode.NUMERIC, repo.ratingScaleMode.value)
        repo.setRatingScaleMode(RatingScaleMode.OFF)
        assertEquals(RatingScaleMode.OFF, repo.ratingScaleMode.value)
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
        assertEquals(RatingScaleMode.OFF, repo.ratingScaleMode.value)
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
        // EXPECTATION CHANGED (hardening, audit tombstone-pref-wipe): applySnapshot
        // no longer applies the snapshot's preference fields at all. Its only
        // production caller is the bundled-seed apply, which must never rewrite user
        // prefs; preference restore belongs to AppJson.apply (disk load / backup
        // restore), which is untouched. A fresh repo therefore keeps its defaults.
        assertEquals(RatingScaleMode.OFF, repo.ratingScaleMode.value)
        assertTrue(repo.useSubstanceColors.value)
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
        // EXPECTATION CHANGED (hardening, contract c): the sibling is now the LOSING
        // body, never the winner's own body. The remote note is newer (200 > 100), so
        // its body wins the merge and the local body is the one preserved.
        assertEquals("local version", resolved.conflictSiblings.first().body)
        assertEquals("remote", resolved.conflictSiblings.first().deviceOrigin)
        assertEquals(100L, resolved.conflictSiblings.first().updatedAt)
        assertEquals("remote version", resolved.body)
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
        // Upsert same event again (same session) - should not duplicate
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

    @Test
    fun searchFindsSingleUpsertWithoutManualRebuild() {
        val repo = JournalRepository()
        repo.upsertSession(Session(id = "s:1", title = "Zebra migration notes", createdAt = 1L,
            updatedAt = 1L, deviceOrigin = "test", startTime = 1L))

        val hits = repo.search("zebra")
        assertTrue(hits.any { it.entityId == "s:1" }, "fresh upsert must be searchable")
    }

    @Test
    fun searchDropsDeletedSession() {
        val repo = JournalRepository()
        repo.upsertSession(Session(id = "s:1", title = "Zebra migration notes", createdAt = 1L,
            updatedAt = 1L, deviceOrigin = "test", startTime = 1L))
        repo.deleteSession("s:1")

        val hits = repo.search("zebra")
        assertTrue(hits.none { it.entityId == "s:1" }, "deleted session must vanish from search")
    }

    // ==================== Search index freshness (dirty-flag rebuild) ====================

    @Test
    fun searchIndexReflectsJustUpsertedAndDeletedNote() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:s", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", body = "changelog about kratom capsules"))
        val hits = repo.search("kratom")
        assertTrue(hits.any { it.entityId == "n:s" }, "just-upserted note must be searchable")
        repo.deleteNote("n:s")
        assertTrue(repo.search("kratom").none { it.entityId == "n:s" }, "deleted note must vanish from search")
    }

    @Test
    fun searchIndexReflectsEntitiesAppliedByBatch() {
        val repo = JournalRepository()
        repo.applyBatch(substances = listOf(sampleSubstance("sub:1", "Psilocybin Mushroom")))
        assertTrue(repo.search("psilocybin").any { it.entityId == "sub:1" },
            "batch-applied substance must be searchable")
    }

    // ==================== upsertSessionChildren (batch save) ====================

    @Test
    fun sessionChildrenBatchMatchesPerItemUpserts() {
        fun seeded(): JournalRepository {
            val repo = JournalRepository()
            repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
            repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
            repo.upsertSession(sampleSession("s:1"))
            return repo
        }
        val doses = listOf(
            sampleDose("d:1", "sub:1", "s:1", 1000L),
            sampleDose("d:2", "sub:2", "s:1", 1500L),
            sampleDose("d:3", "sub:1", "s:1", 2000L, amount = 50.0)
        )
        val events = listOf(
            TimelineEvent(id = "e:1", sessionId = "s:1", timestamp = 1000L,
                eventType = TimelineEventType.ONSET, label = "Start",
                createdAt = 0L, updatedAt = 0L, deviceOrigin = "test"),
            TimelineEvent(id = "e:2", sessionId = "s:1", timestamp = 5000L,
                eventType = TimelineEventType.PEAK, label = "Peak",
                createdAt = 0L, updatedAt = 0L, deviceOrigin = "test")
        )

        val perItem = seeded()
        doses.forEach { perItem.upsertDose(it) }
        events.forEach { perItem.upsertTimelineEvent(it) }

        val batch = seeded()
        val toleranceBefore = batch.toleranceVersion.value
        batch.upsertSessionChildren("s:1", doses, events)

        assertEquals(perItem.doses.value, batch.doses.value, "same stored doses")
        assertEquals(perItem.timelineEvents.value, batch.timelineEvents.value, "same stored events")
        assertEquals(perItem.dosesForSession("s:1"), batch.dosesForSession("s:1"))
        assertEquals(perItem.eventsForSession("s:1"), batch.eventsForSession("s:1"))
        assertEquals(perItem.sessionIdsForSubstance("sub:1").toSet(), batch.sessionIdsForSubstance("sub:1").toSet())
        assertEquals(perItem.sessionIdsForSubstance("sub:2").toSet(), batch.sessionIdsForSubstance("sub:2").toSet())
        assertEquals(perItem.substanceDoseStats, batch.substanceDoseStats,
            "batch stats must equal the incrementally maintained stats")
        assertEquals(toleranceBefore + 1, batch.toleranceVersion.value,
            "batch bumps tolerance exactly once (per-item path bumps once per dose)")
    }

    @Test
    fun sessionChildrenReparentDraftIdRows() {
        val repo = JournalRepository()
        val draftId = "session:draft:test"
        val draftDose = sampleDose("d:1", "sub:1", draftId, 1000L)
        val draftEvent = TimelineEvent(id = "e:1", sessionId = draftId, timestamp = 1000L,
            eventType = TimelineEventType.ONSET, label = "Start",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test")
        repo.upsertSessionChildren("s:1", listOf(draftDose), listOf(draftEvent))
        assertEquals(1, repo.dosesForSession("s:1").size)
        assertTrue(repo.dosesForSession(draftId).isEmpty(), "draft dose must leave the draft session")
        assertEquals(1, repo.eventsForSession("s:1").size)
        assertTrue(repo.eventsForSession(draftId).isEmpty(), "draft event must leave the draft session")
        assertEquals(setOf("s:1"), repo.sessionIdsForSubstance("sub:1").toSet())
    }

    @Test
    fun sessionChildrenEmptyBatchIsNoop() {
        val repo = JournalRepository()
        val mutationsBefore = repo.mutationCount.value
        val toleranceBefore = repo.toleranceVersion.value
        repo.upsertSessionChildren("s:1", emptyList(), emptyList())
        assertEquals(mutationsBefore, repo.mutationCount.value)
        assertEquals(toleranceBefore, repo.toleranceVersion.value)
    }

    // ==================== Seed apply preserves tombstones + prefs ====================

    @Test
    fun applySnapshotPreservesTombstonesAndUserPrefs() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:gone"))
        repo.deleteSession("s:gone")
        repo.setWelcomeCompleted(true)
        repo.setRatingScaleMode(RatingScaleMode.NUMERIC)
        repo.setSubstanceColors(false)
        val tombstonesBefore = repo.exportTombstones()
        assertTrue(tombstonesBefore.isNotEmpty(), "fixture must record a tombstone")

        // A bundled-seed snapshot carries its own (default) prefs and no tombstones.
        repo.applySnapshot(JournalSnapshot(
            savedAt = 1000L,
            substances = listOf(sampleSubstance("sub:1", "LSD")),
            useSubstanceColors = true,
            welcomeCompleted = false
        ))

        val tombstonesAfter = repo.exportTombstones()
        for ((key, deletedAt) in tombstonesBefore) {
            assertEquals(deletedAt, tombstonesAfter[key], "recorded tombstone $key must survive seed apply")
        }
        assertTrue(repo.welcomeCompleted.value, "welcomeCompleted=true must survive seed apply")
        assertEquals(RatingScaleMode.NUMERIC, repo.ratingScaleMode.value,
            "user rating preference must survive seed apply")
        assertFalse(repo.useSubstanceColors.value, "user color preference must survive seed apply")
        // The snapshot's entities still land on top of existing data.
        assertEquals(1, repo.substances.value.size)
    }

    // ==================== Conflict merge (contract c) ====================

    @Test
    fun conflictMergeKeepsBothBodiesWhenLocalWins() {
        val repo = JournalRepository()
        val local = Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "local",
            sessionId = "s:1", body = "newer local body")
        repo.upsertNote(local)
        val incoming = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "remote",
            sessionId = "s:1", body = "older remote body")
        repo.upsertNoteWithConflict(incoming, "device-b")
        val stored = repo.notes.value.first { it.id == "n:1" }
        assertEquals("newer local body", stored.body)
        assertEquals(1, stored.conflictSiblings.size)
        assertEquals("older remote body", stored.conflictSiblings[0].body)
        assertEquals(100L, stored.conflictSiblings[0].updatedAt)
        assertEquals(200L, stored.updatedAt, "winner keeps its own timestamp (no inflation)")
    }

    @Test
    fun upsertNoteWithConflictIsIdempotentOnReplay() = runBlocking {
        val repo = JournalRepository()
        val local = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "local body")
        repo.upsertNote(local)
        val remote = Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "remote",
            sessionId = "s:1", body = "remote body")
        val first = repo.upsertNoteWithConflict(remote, "device-b")
        val second = repo.upsertNoteWithConflict(remote, "device-b")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, second, "re-applying the same remote note must be a no-op")
        assertEquals(1, second.conflictSiblings.size, "sibling must not duplicate on replay")
        assertEquals(1, repo.pendingConflictCount.first())
    }

    @Test
    fun pendingConflictCountCountsOnlyConflictedNotes() = runBlocking {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "plain note one"))
        assertEquals(0, repo.pendingConflictCount.first())
        repo.upsertNote(Note(id = "n:2", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "will conflict"))
        repo.upsertNoteWithConflict(Note(id = "n:2", createdAt = 0L, updatedAt = 200L,
            deviceOrigin = "remote", sessionId = "s:1", body = "rival body"), "device-b")
        assertEquals(1, repo.pendingConflictCount.first())
        repo.upsertNote(Note(id = "n:3", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", body = "plain note two"))
        assertEquals(1, repo.pendingConflictCount.first(),
            "only notes with non-empty conflictSiblings count")
    }

    @Test
    fun applyBatchNoteBranchKeepsBothBodiesBothOrderings() = runBlocking {
        val repo = JournalRepository()
        // Ordering 1: local newer than the peer note.
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "local",
            sessionId = "s:1", body = "local newer"))
        // Ordering 2: peer note newer than the local note.
        repo.upsertNote(Note(id = "n:2", createdAt = 0L, updatedAt = 100L, deviceOrigin = "local",
            sessionId = "s:1", body = "local older"))

        repo.applyBatch(
            notes = listOf(
                Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "peer",
                    sessionId = "s:1", body = "peer older"),
                Note(id = "n:2", createdAt = 0L, updatedAt = 200L, deviceOrigin = "peer",
                    sessionId = "s:1", body = "peer newer")
            ),
            lastWriterWins = true
        )

        val n1 = repo.notes.value.first { it.id == "n:1" }
        assertEquals("local newer", n1.body)
        assertEquals("peer older", n1.conflictSiblings.single().body)
        val n2 = repo.notes.value.first { it.id == "n:2" }
        assertEquals("peer newer", n2.body)
        assertEquals("local older", n2.conflictSiblings.single().body)
        assertEquals(2, repo.pendingConflictCount.first())
    }

    @Test
    fun applyBatchIdenticalBodyDoesNotRegressUpdatedAt() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "local",
            sessionId = "s:1", body = "same body"))
        repo.applyBatch(
            notes = listOf(Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "peer",
                sessionId = "s:1", body = "same body")),
            lastWriterWins = true
        )
        val stored = repo.notes.value.first { it.id == "n:1" }
        assertEquals(200L, stored.updatedAt, "an older identical note must not roll updatedAt back")
        assertTrue(stored.conflictSiblings.isEmpty())
    }

    @Test
    fun applyBatchConflictReplayIsIdempotent() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "local",
            sessionId = "s:1", body = "local body"))
        val peer = Note(id = "n:1", createdAt = 0L, updatedAt = 100L, deviceOrigin = "peer",
            sessionId = "s:1", body = "peer body")
        repo.applyBatch(notes = listOf(peer), lastWriterWins = true)
        val afterFirst = repo.notes.value.first { it.id == "n:1" }
        repo.applyBatch(notes = listOf(peer), lastWriterWins = true)
        val afterSecond = repo.notes.value.first { it.id == "n:1" }
        assertEquals(afterFirst, afterSecond, "re-applying the same conflict batch must change nothing")
        assertEquals("local body", afterSecond.body)
        assertEquals(1, afterSecond.conflictSiblings.size)
    }

    @Test
    fun applyBatchSeedPathBlindOverwritesNotesWithoutConflict() {
        val repo = JournalRepository()
        repo.upsertNote(Note(id = "n:1", createdAt = 0L, updatedAt = 200L, deviceOrigin = "local",
            sessionId = "s:1", body = "stored body"))
        // Seed/restore path (lastWriterWins = false, the default) stays authoritative.
        repo.applyBatch(notes = listOf(Note(id = "n:1", createdAt = 0L, updatedAt = 100L,
            deviceOrigin = "seed", sessionId = "s:1", body = "seed body")))
        val stored = repo.notes.value.first { it.id == "n:1" }
        assertEquals("seed body", stored.body)
        assertTrue(stored.conflictSiblings.isEmpty(), "authoritative restore must not create conflicts")
    }

    // ==================== Dose index / stat integrity ====================

    @Test
    fun upsertDoseReparentUpdatesSessionAndSubstanceIndices() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        val dose = sampleDose("d:1", "sub:1", "s:1", 1000L)
        repo.upsertDose(dose)
        repo.upsertDose(dose.copy(sessionId = "s:2"))
        assertTrue(repo.dosesForSession("s:1").isEmpty(), "old session must lose the moved dose")
        assertEquals(1, repo.dosesForSession("s:2").size, "new session holds exactly one copy")
        assertEquals(setOf("s:2"), repo.sessionIdsForSubstance("sub:1").toSet(),
            "stale session must leave the substance index")
        val stats = repo.substanceDoseStats["sub:1"]
        assertNotNull(stats)
        assertEquals(1, stats.first)
        assertEquals(1000L, stats.second)
    }

    @Test
    fun upsertDoseSubstanceChangeMovesIndicesAndStats() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        val dose = sampleDose("d:1", "sub:1", "s:1", 1000L)
        repo.upsertDose(dose)
        repo.upsertDose(dose.copy(substanceId = "sub:2"))
        assertTrue(repo.sessionIdsForSubstance("sub:1").isEmpty(), "old substance index must be cleaned")
        assertEquals(setOf("s:1"), repo.sessionIdsForSubstance("sub:2").toSet())
        assertFalse(repo.substanceDoseStats.containsKey("sub:1"))
        assertEquals(1, repo.substanceDoseStats["sub:2"]?.first)
    }

    @Test
    fun deleteDoseKeepsSessionIndexWhenSiblingDoseRemains() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))
        repo.deleteDose("d:1")
        assertEquals(setOf("s:1"), repo.sessionIdsForSubstance("sub:1").toSet(),
            "the session still holds d:2 and must stay indexed")
        assertEquals(1, repo.substanceDoseStats["sub:1"]?.first)
    }

    @Test
    fun substanceDoseStatsSurviveCreateDeleteReparentRebuild() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:1", 2000L))

        repo.deleteDose("d:1")
        // The incremental delete path must already be consistent with no rebuild:
        // the session still holds d:2, so it stays indexed.
        assertEquals(setOf("s:1"), repo.sessionIdsForSubstance("sub:1").toSet())
        assertEquals(1, repo.substanceDoseStats["sub:1"]?.first)

        // Re-parent the survivor: incremental stats must shrink to one session again.
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 2000L))
        val statsBefore = repo.substanceDoseStats
        val sessionsBefore = repo.sessionIdsForSubstance("sub:1").toSet()
        assertEquals(setOf("s:2"), sessionsBefore, "stale session must not survive the re-parent")
        assertEquals(1, statsBefore["sub:1"]?.first, "distinct-session count must shrink with the move")

        // Roundtrip: a from-scratch rebuild must reproduce the incremental state.
        repo.rebuildIndices()
        assertEquals(statsBefore, repo.substanceDoseStats,
            "incremental stats must equal rebuildSubstanceDoseStats output")
        assertEquals(sessionsBefore, repo.sessionIdsForSubstance("sub:1").toSet())
    }

    @Test
    fun sessionCascadeKeepsSubstanceSessionIndexConsistent() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 2000L))
        repo.deleteSession("s:1") // removeWhere cascade path
        assertEquals(setOf("s:2"), repo.sessionIdsForSubstance("sub:1").toSet())
        val statsBefore = repo.substanceDoseStats
        assertEquals(1, statsBefore["sub:1"]?.first)
        repo.rebuildIndices()
        assertEquals(statsBefore, repo.substanceDoseStats,
            "cascade must run the same invalidation a full rebuild produces")
    }

    // ==================== applyBatch tolerance bump ====================

    @Test
    fun applyBatchBumpsToleranceVersionOnceForDoseAndSubstancePuts() {
        val repo = JournalRepository()
        val before = repo.toleranceVersion.value
        repo.applyBatch(
            doses = listOf(sampleDose("d:1", "sub:1", "s:1", 1000L)),
            substances = listOf(sampleSubstance("sub:1", "LSD"))
        )
        assertEquals(before + 1, repo.toleranceVersion.value, "exactly one bump per batch")

        val beforeNotes = repo.toleranceVersion.value
        repo.applyBatch(notes = listOf(Note(id = "n:1", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", sessionId = "s:1", body = "x")))
        assertEquals(beforeNotes, repo.toleranceVersion.value, "notes do not affect tolerance")
    }

    // ==================== Derived flows for the UI wave ====================

    @Test
    fun dosesForSubstanceFlowFilters() = runBlocking {
        val repo = JournalRepository()
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:2", "s:1", 2000L))
        assertEquals(listOf("d:1"), repo.dosesForSubstance("sub:1").first().map { it.id })
        repo.upsertDose(sampleDose("d:3", "sub:1", "s:2", 3000L))
        assertEquals(listOf("d:1", "d:3"), repo.dosesForSubstance("sub:1").first().map { it.id })
        assertTrue(repo.dosesForSubstance("sub:none").first().isEmpty())
    }

    @Test
    fun dosesForSubstanceFlowSkipsUnrelatedMutations() = runBlocking {
        val repo = JournalRepository()
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        val emissions = mutableListOf<List<Dose>>()
        val collector = launch { repo.dosesForSubstance("sub:1").collect { emissions.add(it) } }
        while (emissions.isEmpty()) yield()
        repo.upsertDose(sampleDose("d:2", "sub:2", "s:1", 2000L)) // different substance
        delay(200)
        assertEquals(1, emissions.size, "unrelated mutation must not re-emit (dedup)")
        repo.upsertDose(sampleDose("d:3", "sub:1", "s:2", 3000L)) // this substance
        delay(200)
        assertEquals(2, emissions.size, "related mutation must re-emit")
        collector.cancel()
    }

    @Test
    fun substanceMapsExposeLiveNames() = runBlocking {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        assertEquals(mapOf("sub:1" to "LSD", "sub:2" to "MDMA"), repo.substanceNamesById.first())
        assertEquals("LSD", repo.substancesById.first()["sub:1"]?.name)
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD-2025"))
        assertEquals("LSD-2025", repo.substanceNamesById.first()["sub:1"])
        assertNull(repo.substancesById.first()["gone"])
    }
}
