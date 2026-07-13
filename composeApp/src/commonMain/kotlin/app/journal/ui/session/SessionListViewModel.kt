package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * ViewModel for [SessionListScreen].
 *
 * Encapsulates all filtering, sorting, and tag-extraction logic that was
 * previously inline in the Composable. Testable without Compose runtime.
 *
 * Create via [create] for production (uses [JournalRepository.instance])
 * or directly with a mock [IJournalRepository] for tests.
 */
class SessionListViewModel(
    val repo: IJournalRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    /** All sessions from the repository. */
    val sessions: StateFlow<List<Session>> = repo.sessions

    // ---- Filter state ----
    val filterTags = MutableStateFlow<Set<String>>(emptySet())
    val showFavoritesOnly = MutableStateFlow(false)
    val showArchived = MutableStateFlow(false)
    val consumerFilter = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("")

    /** Distinct tags across all sessions, sorted. */
    val allTags = sessions.map { list ->
        list.flatMap { it.tags }.distinct().sorted()
    }

    /** Distinct consumer names across all sessions, sorted. */
    val allConsumers = sessions.map { list ->
        list.mapNotNull { it.consumerName }.distinct().sorted()
    }

    /** Filtered and sorted sessions derived from filter state. */
    val filteredSessions = combine(
        sessions, filterTags, showFavoritesOnly, showArchived,
        consumerFilter, searchQuery
    ) { all, tags, favsOnly, archived, consumer, query ->
        val q = if (query.isNotBlank()) query.lowercase() else null
        all
            .filter { s ->
                if (tags.isNotEmpty() && s.tags.none { it in tags }) return@filter false
                if (favsOnly && !s.isFavorite) return@filter false
                if (!archived && s.isArchived) return@filter false
                if (consumer != null && s.consumerName != consumer) return@filter false
                if (q != null) {
                    s.title.lowercase().contains(q) ||
                    s.tags.any { it.lowercase().contains(q) } ||
                    s.intention?.lowercase()?.contains(q) == true
                } else true
            }
            .sortedByDescending { it.startTime }
    }

    fun toggleTag(tag: String) {
        filterTags.value = if (tag in filterTags.value) {
            filterTags.value - tag
        } else {
            filterTags.value + tag
        }
    }

    fun clearFilters() {
        filterTags.value = emptySet()
        showFavoritesOnly.value = false
        showArchived.value = false
        consumerFilter.value = null
        searchQuery.value = ""
    }

    companion object {
        fun create(): SessionListViewModel = SessionListViewModel(JournalRepository.instance)
    }
}

/**
 * Combines multiple StateFlows into a single flow using [kotlinx.coroutines.flow.combine].
 * Kotlin's built-in combine only takes up to 5 flows; this wraps 6.
 */
private fun <T1, T2, T3, T4, T5, T6, R> combine(
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
            args[0] as T1, args[1] as T2, args[2] as T3,
            args[3] as T4, args[4] as T5, args[5] as T6
        )
    }
)
