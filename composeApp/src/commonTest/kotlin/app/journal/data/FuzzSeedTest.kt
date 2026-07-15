package app.journal.data

import app.journal.model.*
import kotlin.test.*

class FuzzSeedTest {

    /** Seed with at least 2 substances so FuzzSeed has data to work with. */
    private fun seedTwoSubstances(repo: IJournalRepository) {
        repo.upsertSubstance(Substance(
            id = "cid:5761", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "system", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "cid:1615", name = "Psilocybin", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "system", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
    }

    @Test
    fun generatePopulatesSubstances() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        assertTrue(repo.substances.value.size >= 2, "expected at least 2 substances after generate")
        assertTrue(repo.sessions.value.isNotEmpty(), "expected sessions after generate")
    }

    @Test
    fun everyDoseReferencesExistingSubstanceAndSession() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val subIds = repo.substances.value.map { it.id }.toSet()
        val sessionIds = repo.sessions.value.map { it.id }.toSet()
        for (dose in repo.doses.value) {
            assertTrue(dose.substanceId in subIds, "dose ${dose.id} references missing substance ${dose.substanceId}")
            assertTrue(dose.sessionId in sessionIds, "dose ${dose.id} references missing session ${dose.sessionId}")
        }
    }

    @Test
    fun everyTimelineEventReferencesExistingSession() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val sessionIds = repo.sessions.value.map { it.id }.toSet()
        for (event in repo.timelineEvents.value) {
            assertTrue(event.sessionId in sessionIds, "event ${event.id} references missing session ${event.sessionId}")
        }
    }

    @Test
    fun sessionStartNoLaterThanEnd() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        for (session in repo.sessions.value) {
            if (session.endTime != null) {
                assertTrue(session.startTime <= session.endTime, "session ${session.id} has start after end")
            }
        }
    }

    @Test
    fun generatedDataUsesFuzzPrefix() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        assertTrue(repo.sessions.value.all { it.id.startsWith("session:fuzz_") },
            "all generated sessions should have fuzz_ prefix in ID")
    }

    @Test
    fun noFuzzWithoutEnoughSubstances() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "cid:5761", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "system", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        // Only 1 substance — FuzzSeed should skip
        FuzzSeed.generate(repo)
        assertTrue(repo.sessions.value.isEmpty(), "no sessions should be generated with < 2 substances")
    }

    @Test
    fun everyDoseAmountIsPositive() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        for (dose in repo.doses.value) {
            assertTrue(dose.amount > 0.0, "dose ${dose.id} has non-positive amount ${dose.amount}")
        }
    }

    @Test
    fun everyTimelineEventTimestampWithinSessionRange() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val sessions = repo.sessions.value.associateBy { it.id }
        for (event in repo.timelineEvents.value) {
            val session = sessions[event.sessionId] ?: continue
            val end = session.endTime ?: session.startTime + 86400000L
            assertTrue(event.timestamp >= session.startTime,
                "event ${event.id} timestamp ${event.timestamp} before session ${session.id} start ${session.startTime}")
            assertTrue(event.timestamp <= end,
                "event ${event.id} timestamp ${event.timestamp} after session ${session.id} end $end")
        }
    }

    @Test
    fun everyTimelineEventHasValidType() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val validTypes = TimelineEventType.entries.toSet()
        for (event in repo.timelineEvents.value) {
            assertTrue(event.eventType in validTypes,
                "event ${event.id} has unknown type ${event.eventType}")
        }
    }

    @Test
    fun noDuplicateEntityIds() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val sessionIds = repo.sessions.value.map { it.id }
        val doseIds = repo.doses.value.map { it.id }
        val eventIds = repo.timelineEvents.value.map { it.id }
        assertEquals(sessionIds.size, sessionIds.distinct().size, "duplicate session IDs")
        assertEquals(doseIds.size, doseIds.distinct().size, "duplicate dose IDs")
        assertEquals(eventIds.size, eventIds.distinct().size, "duplicate timeline event IDs")
    }

    @Test
    fun everyCheckinTimestampWithinSessionRange() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val sessions = repo.sessions.value.associateBy { it.id }
        for (session in repo.sessions.value) {
            val end = session.endTime ?: session.startTime + 86400000L
            for ((ci, checkin) in session.checkins.withIndex()) {
                assertTrue(checkin.timestamp >= session.startTime,
                    "session ${session.id} checkin $ci timestamp ${checkin.timestamp} before start ${session.startTime}")
                assertTrue(checkin.timestamp <= end,
                    "session ${session.id} checkin $ci timestamp ${checkin.timestamp} after end $end")
            }
        }
    }

    @Test
    fun everySessionHasAtLeastOneDose() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        val dosesBySession = repo.doses.value.groupBy { it.sessionId }
        for (session in repo.sessions.value) {
            val doses = dosesBySession[session.id]
            assertNotNull(doses, "session ${session.id} has no doses")
            assertTrue(doses.isNotEmpty(), "session ${session.id} has zero doses")
        }
    }

    @Test
    fun sessionRatingInValidRange() {
        val repo = JournalRepository()
        seedTwoSubstances(repo)
        FuzzSeed.generate(repo)
        for (session in repo.sessions.value) {
            if (session.rating != null) {
                assertTrue(session.rating in 3..10,
                    "session ${session.id} has out-of-range rating ${session.rating}")
            }
        }
    }
}
