package app.journal.model

import app.journal.data.JournalRepository
import kotlin.test.*

class ToleranceCalculatorTest {

    private val dayMs = 86400000L

    private fun makeRepoWithDoses(
        substanceId: String, substanceName: String,
        doseTimestamps: List<Long>, now: Long = 1_700_000_000_000L
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
        repo.upsertSubstance(Substance(
            id = "sub:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = "LSD", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        assertTrue(ToleranceCalculator.calculate(repo).isEmpty())
    }

    @Test
    fun recentFrequentUseIsHigh() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD",
            listOf(now - 2 * dayMs, now - 1 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.HIGH, info.level)
        assertEquals(2, info.totalDosesLast30Days)
    }

    @Test
    fun oldUseIsNone() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 20 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.NONE, info.level)
    }

    @Test
    fun moderateUseWithinSevenDays() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 5 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.MEDIUM, info.level)
    }

    @Test
    fun lowUseWithin14Days() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 10 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.LOW, info.level)
    }

    @Test
    fun exactBoundaryThreeDaysSingleDoseIsMediumNotHigh() {
        val now = 1_700_000_000_000L
        // 3 days ago, single dose — need BOTH daysSince <= 3 AND dosesLast30Days >= 2 for HIGH
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 3 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.MEDIUM, info.level)
    }

    @Test
    fun exactBoundarySevenDays() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 7 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.MEDIUM, info.level)
    }

    @Test
    fun exactBoundaryFourteenDays() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 14 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.LOW, info.level)
    }

    @Test
    fun justOverThreeDaysIsMedium() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD", listOf(now - 4 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(ToleranceLevel.MEDIUM, info.level)
    }

    @Test
    fun multipleSubstancesReturnMultipleResults() {
        val now = 1_700_000_000_000L
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = "LSD", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "sub:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = "MDMA", substanceClass = listOf("Empathogen"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertDose(Dose(id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:1", routeOfAdministration = "Oral",
            amount = 100.0, unit = "mg", timestamp = now - 2 * dayMs))
        repo.upsertDose(Dose(id = "d:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:2", substanceId = "sub:2", routeOfAdministration = "Oral",
            amount = 120.0, unit = "mg", timestamp = now - 5 * dayMs))
        val results = ToleranceCalculator.calculate(repo, now)
        assertEquals(2, results.size)
    }

    @Test
    fun dosesSortedByRecency() {
        val now = 1_700_000_000_000L
        val repo = makeRepoWithDoses("sub:1", "LSD",
            listOf(now - 20 * dayMs, now - 1 * dayMs), now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        // Most recent dose is 1 day ago
        assertTrue(info.hoursSinceLastDose < 48 * 3600)
        assertTrue(info.daysSinceLastDose < 2.0)
    }

    @Test
    fun thirtyDayDoseCountIsAccurate() {
        val now = 1_700_000_000_000L
        val timestamps = listOf(
            now - 5 * dayMs, now - 10 * dayMs, now - 20 * dayMs, // within 30 days
            now - 35 * dayMs // outside 30 days
        )
        val repo = makeRepoWithDoses("sub:1", "LSD", timestamps, now)
        val info = ToleranceCalculator.calculate(repo, now).first()
        assertEquals(3, info.totalDosesLast30Days)
    }
}
