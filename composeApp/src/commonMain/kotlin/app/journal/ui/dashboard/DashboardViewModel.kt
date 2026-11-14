package app.journal.ui.dashboard

import app.journal.data.IJournalRepository
import app.journal.data.ToleranceCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Dashboard data derived once per data change and kept, so a tab visit does
 * not rebuild the maps over every dose. [toleranceCalculator] carries its own
 * per-version cache, which only pays off when one instance outlives the screen.
 */
class DashboardViewModel(
    repo: IJournalRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    val toleranceCalculator = ToleranceCalculator(repo)

    val substanceNames: StateFlow<Map<String, String>> = repo.substances
        .map { substances -> substances.associate { it.id to it.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val substanceSessionPairs: StateFlow<List<Pair<String, String>>> =
        combine(repo.doses, substanceNames) { doses, names ->
            doses.mapNotNull { dose -> names[dose.substanceId]?.let { name -> name to dose.sessionId } }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val substancesByDate: StateFlow<Map<LocalDate, List<String>>> =
        combine(repo.doses, substanceNames) { doses, names ->
            val tz = TimeZone.currentSystemDefault()
            val byDate = mutableMapOf<LocalDate, MutableSet<String>>()
            for (dose in doses) {
                val date = Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(tz).date
                byDate.getOrPut(date) { mutableSetOf() }.add(names[dose.substanceId] ?: dose.substanceId)
            }
            byDate.mapValues { (_, value) -> value.sorted() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    companion object {
        fun create(repo: IJournalRepository): DashboardViewModel = DashboardViewModel(repo)
    }
}