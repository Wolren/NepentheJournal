package app.journal.model

import androidx.compose.runtime.Immutable
import app.journal.data.IJournalRepository
import app.journal.util.currentTimeMillis

/**
 * Simple tolerance estimate based on recency and frequency of ingestion.
 * Per-substance tolerance levels help gauge current sensitivity.
 *
 * Instance-based so callers (Composables) can hold a `remember`-ed instance
 * and get proper caching without static state leaking across tests.
 */
@Immutable
data class ToleranceInfo(
    val substanceName: String,
    val substanceId: String,
    val substanceClass: List<String>,
    val level: ToleranceLevel,
    val daysSinceLastDose: Double,
    val hoursSinceLastDose: Long,
    val lastDoseAmount: Double,
    val lastDoseUnit: String,
    val lastDoseRoute: String,
    val lastDoseTimestamp: Long,
    val totalDosesLast30Days: Int
)

enum class ToleranceLevel {
    HIGH, MEDIUM, LOW, NONE
}

class ToleranceCalculator(private val repo: IJournalRepository) {

    private var cachedVersion: Int = -1
    private var cachedResult: List<ToleranceInfo> = emptyList()
    private var hasCached: Boolean = false

    /**
     * Returns tolerance info for all substances that have been ingested.
     * Sorted by most recently used first.
     * @param now Reference "now" timestamp. Defaults to the live clock so callers
     *            (UI) don't change; tests pass a fixed value for determinism.
     */
    fun calculate(now: Long = currentTimeMillis()): List<ToleranceInfo> {
        val currentVersion = repo.toleranceVersion.value
        if (currentVersion == cachedVersion && hasCached) {
            return cachedResult
        }

        val dayMs = 86400000L

        val cutoff = now - 45L * dayMs
        val recentDoses = repo.doses.value.filter { it.timestamp > cutoff }

        val dosesBySubstance = recentDoses.groupBy { it.substanceId }

        val result = dosesBySubstance.mapNotNull { (substanceId, doses) ->
            val substance = repo.getSubstance(substanceId) ?: return@mapNotNull null
            val sorted = doses.sortedByDescending { it.timestamp }
            val mostRecent = sorted.first()

            val elapsedMs = now - mostRecent.timestamp
            val daysSince = elapsedMs / (dayMs.toDouble())
            val hoursSince = elapsedMs / 3600000L

            val dosesLast30Days = sorted.count { now - it.timestamp < 30L * dayMs }

            val level = when {
                daysSince <= 3 && dosesLast30Days >= 2 -> ToleranceLevel.HIGH
                daysSince <= 7 -> ToleranceLevel.MEDIUM
                daysSince <= 14 -> ToleranceLevel.LOW
                else -> ToleranceLevel.NONE
            }

            ToleranceInfo(
                substanceName = substance.name,
                substanceId = substanceId,
                substanceClass = substance.substanceClass,
                level = level,
                daysSinceLastDose = daysSince,
                hoursSinceLastDose = hoursSince,
                lastDoseAmount = mostRecent.amount,
                lastDoseUnit = mostRecent.unit,
                lastDoseRoute = mostRecent.routeOfAdministration,
                lastDoseTimestamp = mostRecent.timestamp,
                totalDosesLast30Days = dosesLast30Days
            )
        }.sortedBy { it.daysSinceLastDose }

        cachedVersion = currentVersion
        cachedResult = result
        hasCached = true
        return result
    }

    companion object {
        /**
         * Convenience for one-shot calculations (tests, quick lookups).
         * Prefer creating an instance and keeping it alive when caching matters.
         */
        fun calculate(repo: IJournalRepository, now: Long = currentTimeMillis()): List<ToleranceInfo> {
            return ToleranceCalculator(repo).calculate(now)
        }
    }
}
