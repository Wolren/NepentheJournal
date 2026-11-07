package app.journal.data

import app.journal.model.StomachFullness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Link contract: scheme, host and parameters, plus the failure mode for unknown substances. */
class QuickLogTest {

    @Test
    fun parsesASubstanceAndAmount() {
        val request = QuickLog.parse("nepenthe://quicklog?substance=4-AcO-DMT&amount=12&unit=mg")
        assertNotNull(request)
        assertEquals("4-AcO-DMT", request.substance)
        assertEquals(12.0, request.amount)
        assertEquals("mg", request.unit)
    }

    @Test
    fun parsesRouteTimeNotesAndMeal() {
        val request = QuickLog.parse(
            "nepenthe://quicklog?substance=Kratom&amount=3&unit=g&route=oral" +
                "&time=2026-10-18T09:30:00Z&meal=EMPTY&notes=late+night&tags=work"
        )
        assertNotNull(request)
        assertEquals("oral", request.route)
        assertEquals(StomachFullness.EMPTY, request.stomachFullness)
        assertEquals("late night", request.notes)
        assertEquals(1_792_315_800_000L, request.timeMillis)
    }

    @Test
    fun acceptsEpochSecondsForTime() {
        val request = QuickLog.parse("nepenthe://quicklog?substance=X&time=1792300000")
        assertEquals(1_792_300_000_000L, request?.timeMillis)
    }

    @Test
    fun ignoresForeignLinks() {
        assertNull(QuickLog.parse("https://example.com/quicklog?substance=X"))
        assertNull(QuickLog.parse("nepenthe://somethingelse?substance=X"))
        assertNull(QuickLog.parse("nepenthe://quicklog?amount=5"))
    }

    @Test
    fun applyWritesTheSessionAndDose() {
        val repo = JournalRepository()
        repo.upsertSubstance(
            app.journal.model.Substance(
                id = "dw:caffeine", createdAt = 0L, updatedAt = 0L, cachedAt = 0L,
                sourceVersion = "test", name = "Caffeine",
                routesOfAdministration = listOf("oral")
            )
        )

        val request = QuickLog.parse("nepenthe://quicklog?substance=caffeine&amount=100&unit=mg")!!
        val outcome = QuickLog.apply(repo, request)

        assertTrue(outcome is QuickLogOutcome.Logged, "expected a logged outcome, got $outcome")
        val session = repo.sessions.value.single()
        assertEquals("Caffeine", session.title)
        val dose = repo.doses.value.single()
        assertEquals(100.0, dose.amount)
        assertEquals("mg", dose.unit)
        assertEquals("dw:caffeine", dose.substanceId)
        assertEquals(session.id, dose.sessionId)
    }

    @Test
    fun unknownSubstanceFailsWithoutWriting() {
        val repo = JournalRepository()
        val request = QuickLog.parse("nepenthe://quicklog?substance=Unobtainium&amount=1")!!
        val outcome = QuickLog.apply(repo, request)
        assertTrue(outcome is QuickLogOutcome.Failed)
        assertTrue(repo.sessions.value.isEmpty(), "a failed quick log must not create a session")
    }
}
