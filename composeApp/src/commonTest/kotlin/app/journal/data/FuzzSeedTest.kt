package app.journal.data

import kotlin.test.*

class FuzzSeedTest {

    @Test
    fun generatePopulatesSubstances() {
        val repo = JournalRepository()
        FuzzSeed.generate(repo)
        assertTrue(repo.substances.value.isNotEmpty(), "expected substances after generate")
        assertTrue(repo.sessions.value.isNotEmpty(), "expected sessions after generate")
    }

    @Test
    fun everyDoseReferencesExistingSubstanceAndSession() {
        val repo = JournalRepository()
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
        FuzzSeed.generate(repo)
        val sessionIds = repo.sessions.value.map { it.id }.toSet()
        for (event in repo.timelineEvents.value) {
            assertTrue(event.sessionId in sessionIds, "event ${event.id} references missing session ${event.sessionId}")
        }
    }

    @Test
    fun sessionStartNoLaterThanEnd() {
        val repo = JournalRepository()
        FuzzSeed.generate(repo)
        for (session in repo.sessions.value) {
            if (session.endTime != null) {
                assertTrue(session.startTime <= session.endTime, "session ${session.id} has start after end")
            }
        }
    }

    @Test
    fun generatedDataIsStableAcrossMultipleRuns() {
        repeat(3) {
            val repo = JournalRepository()
            FuzzSeed.generate(repo)
            assertTrue(repo.substances.value.size >= 5, "expected at least 5 substances")
        }
    }
}
