package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

data class SubstanceFilterItem(val id: String, val name: String)

class SessionListViewModel(
    @PublishedApi internal val repo: IJournalRepository = JournalRepository.instance,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    /** All sessions from the repository. */
    val sessions: StateFlow<List<Session>> = repo.sessions

    // ---- Filter state ----
    val filterSubstanceIds = MutableStateFlow<Set<String>>(emptySet())
    val showFavoritesOnly = MutableStateFlow(false)
    val showArchived = MutableStateFlow(false)
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
    val filteredSessions = combine6(
        sessions, filterSubstanceIds, showFavoritesOnly, showArchived,
        consumerFilter, searchQuery
    ) { all: List<Session>, subIds: Set<String>, favsOnly: Boolean, archived: Boolean, consumer: String?, query: String ->
        val q = if (query.isNotBlank()) query.lowercase() else null
        val matchingSessionIds = if (subIds.isEmpty()) null
            else buildSet<String> {
                for (s in all) {
                    for (d in repo.dosesForSession(s.id)) {
                        if (d.substanceId in subIds) {
                            add(s.id)
                            break
                        }
                    }
                }
            }
        all
            .filter { s ->
                if (matchingSessionIds != null && s.id !in matchingSessionIds) return@filter false
                if (favsOnly && !s.isFavorite) return@filter false
                if (!archived && s.isArchived) return@filter false
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
        showArchived.value = false
        consumerFilter.value = null
        searchQuery.value = ""
    }

    companion object {
        fun create(repo: IJournalRepository = JournalRepository.instance): SessionListViewModel =
            SessionListViewModel(repo)
    }
}

/**
 * Kotlin's built-in combine only takes up to 5 flows.
 * This provides a 6-flow variant.
 */
private fun <T1, T2, T3, T4, T5, T6, R> combine6(
    flow1: kotlinx.coroutines.flow.Flow<T1>,
    flow2: kotlinx.coroutines.flow.Flow<T2>,
    flow3: kotlinx.coroutines.flow.Flow<T3>,
    flow4: kotlinx.coroutines.flow.Flow<T4>,
    flow5: kotlinx.coroutines.flow.Flow<T5>,
    flow6: kotlinx.coroutines.flow.Flow<T6>,
    transform: suspend (T1, T2, T3, T4, T5, T6) -> R
): kotlinx.coroutines.flow.Flow<R> = kotlinx.coroutines.flow.combine(
    flow1, flow2, flow3, flow4, flow5, flow6,
    transform = { args: Array<*> ->
        @Suppress("UNCHECKED_CAST")
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6
        )
    }
)
