package app.journal.ui.session

import app.journal.data.IJournalRepository
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
    /** All sessions from the repository. */
    val sessions: StateFlow<List<Session>> = repo.sessions

    // ---- Filter state ----
    val filterSubstanceIds = MutableStateFlow<Set<String>>(emptySet())
    val showFavoritesOnly = MutableStateFlow(false)
    val consumerFilter = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("")

    /** Distinct substances used across all sessions, sorted by name. */
    val allSessionSubstances = combine(repo.doses, repo.substances) { doses, subs ->
        val nameMap = subs.associate { it.id to it.name }
        doses.map { it.substanceId }
            .distinct()
            .mapNotNull { id -> nameMap[id]?.let { name -> SubstanceFilterItem(id, name) } }
            .sortedBy { it.name }
    }

    /** Distinct consumer names across all sessions, sorted. */
    val allConsumers = sessions.map { list ->
        list.mapNotNull { it.consumerName }.distinct().sorted()
    }

    /** Filtered and sorted sessions derived from filter state. */
    val filteredSessions = combine(
        sessions, filterSubstanceIds, showFavoritesOnly,
        consumerFilter, searchQuery
    ) { all: List<Session>, subIds: Set<String>, favsOnly: Boolean, consumer: String?, query: String ->
        val q = if (query.isNotBlank()) query.lowercase() else null

        // Batch-resolve matching session IDs in a single lock acquire via the precomputed index
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
