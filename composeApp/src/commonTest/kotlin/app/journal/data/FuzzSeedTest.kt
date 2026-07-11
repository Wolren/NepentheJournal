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
    fun generatedDataIsStableAcrossMultipleRuns() {
        repeat(3) {
            val repo = JournalRepository()
            seedTwoSubstances(repo)
            FuzzSeed.generate(repo)
            assertTrue(repo.substances.value.size >= 2, "expected at least 2 substances")
        }
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
}
