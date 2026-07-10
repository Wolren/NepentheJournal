package app.journal.model

import app.journal.data.JournalRepository
import kotlin.test.*

class ToleranceCalculatorTest {

    private fun makeRepoWithDoses(
        substanceId: String,
        substanceName: String,
        doseTimestamps: List<Long>,
        now: Long
    ): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSubstance(
            Substance(
                id = substanceId, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                name = substanceName, substanceClass = listOf("Classical Psychedelic"),
                cachedAt = 0L, sourceVersion = "test"
            )
        )
        doseTimestamps.forEachIndexed { i, ts ->
            repo.upsertDose(
                Dose(
                    id = "d:$i", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "s:$i", substanceId = substanceId,
                    routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = ts
                )
            )
        }
        return repo
    }

    @Test
    fun noDosesYieldsEmptyList() {
        val repo = JournalRepository()
        repo.upsertSubstance(
            Substance(
                id = "sub:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                name = "LSD", substanceClass = listOf("Classical Psychedelic"),
                cachedAt = 0L, sourceVersion = "test"
            )
        )
        assertTrue(ToleranceCalculator.calculate(repo).isEmpty())
    }

    @Test
    fun recentFrequentUseIsHigh() {
        val now = 1_700_000_000_000L
        // 2 days ago + 1 day ago = within 3 days and >= 2 doses in 30d
        val repo = makeRepoWithDoses("sub:1", "LSD",
            listOf(now - 2L * 86400000L, now - 1L * 86400000L), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.HIGH, info.level)
        assertEquals(2, info.totalDosesLast30Days)
    }

    @Test
    fun oldUseIsNone() {
        val now = 1_700_000_000_000L
        // 20 days ago, single dose
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 20L * 86400000L), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.NONE, info.level)
    }

    @Test
    fun moderateUseWithinSevenDays() {
        val now = 1_700_000_000_000L
        // 5 days ago, single dose
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 5L * 86400000L), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.MEDIUM, info.level)
    }
}
