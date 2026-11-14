package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

data class SubstanceFilterItem(val id: String, val name: String)

class SessionListViewModel(
    @PublishedApi internal val repo: IJournalRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    val sessions: StateFlow<List<Session>> = repo.sessions

    /** Grouped once per data change and kept, so a tab revisit does not regroup every dose. */
    val dosesBySession: StateFlow<Map<String, List<Dose>>> = repo.doses
        .map { doses -> doses.groupBy { it.sessionId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Name lookup kept alive for the same reason. */
    val substanceNameMap: StateFlow<Map<String, String>> = repo.substances
        .map { substances -> substances.associate { it.id to it.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val filterSubstanceIds = MutableStateFlow<Set<String>>(emptySet())
    val showFavoritesOnly = MutableStateFlow(false)
    val consumerFilter = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("")

    val allSessionSubstances: StateFlow<List<SubstanceFilterItem>> =
        combine(repo.doses, repo.substances) { doses, subs ->
            val nameMap = subs.associate { it.id to it.name }
            doses.map { it.substanceId }
                .distinct()
                .mapNotNull { id -> nameMap[id]?.let { name -> SubstanceFilterItem(id, name) } }
                .sortedBy { it.name }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val allConsumers = sessions.map { list ->
        list.mapNotNull { it.consumerName }.distinct().sorted()
    }

    val filteredSessions = combine(
        sessions, filterSubstanceIds, showFavoritesOnly,
        consumerFilter, searchQuery
    ) { all: List<Session>, subIds: Set<String>, favsOnly: Boolean, consumer: String?, query: String ->
        val q = if (query.isNotBlank()) query.lowercase() else null

        val matchingSessionIds = if (subIds.isEmpty()) null
            else repo.sessionIdsForSubstances(subIds)

        all
            .filter { s ->
                if (matchingSessionIds != null && s.id !in matchingSessionIds) return@filter false
                if (favsOnly && !s.isFavorite) return@filter false
                if (consumer != null && s.consumerName != consumer) return@filter false
                if (q != null) {
                    s.title.lowercase().contains(q) ||
                    s.intention?.lowercase()?.contains(q) == true
                } else true
            }
            .sortedByDescending { it.startTime }
    }

    fun toggleSubstance(substanceId: String) {
        filterSubstanceIds.value = if (substanceId in filterSubstanceIds.value) {
            filterSubstanceIds.value - substanceId
        } else {
            filterSubstanceIds.value + substanceId
        }
    }

    fun clearFilters() {
        filterSubstanceIds.value = emptySet()
        showFavoritesOnly.value = false
        consumerFilter.value = null
        searchQuery.value = ""
    }

    companion object {
        fun create(repo: IJournalRepository): SessionListViewModel =
            SessionListViewModel(repo)
    }
}
