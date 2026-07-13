package app.journal.ui.substances

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Substance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * ViewModel for [SubstanceScreen].
 *
 * Moves filtering, category selection, and search logic out of the Composable
 * so it can be tested independently of the Compose runtime.
 */
class SubstanceScreenViewModel(
    private val repo: IJournalRepository
) {
    /** All substances from the repository. */
    val substances: Flow<List<Substance>> = repo.substances

    /** Substances that have at least one route of administration, effect, or dosage band. */
    val realSubstances: Flow<List<Substance>> = substances.map { list ->
        list.filter { sub ->
            sub.routesOfAdministration.isNotEmpty() ||
            sub.effects.isNotEmpty() ||
            sub.dosageBands.isNotEmpty()
        }
    }

    /** All unique substance classes across real substances, sorted. */
    val allCategories: Flow<List<String>> = realSubstances.map { list ->
        list.flatMap { it.substanceClass }.distinct().sorted()
    }

    /** Current search query. */
    val query = MutableStateFlow("")

    /** Active category filters. */
    val activeCategories = MutableStateFlow<Set<String>>(emptySet())

    /** Precomputed dose stats from repository. */
    val substanceDoseStats: Map<String, Pair<Int, Long>>
        get() = repo.substanceDoseStats

    /** Filtered + searched results. */
    val results: Flow<List<Substance>> = combine(
        realSubstances, query, activeCategories
    ) { all, q, cats ->
        var result = all

        // Category filter
        if (cats.isNotEmpty()) {
            result = result.filter { sub ->
                sub.substanceClass.any { it in cats }
            }
        }

        // Text search
        if (q.isNotBlank()) {
            val lq = q.lowercase()
            result = result.filter { sub ->
                sub.name.lowercase().contains(lq) ||
                sub.aliases.any { it.lowercase().contains(lq) } ||
                sub.substanceClass.any { it.lowercase().contains(lq) }
            }
        }

        result
    }

    fun toggleCategory(cat: String) {
        activeCategories.value = if (cat in activeCategories.value) {
            activeCategories.value - cat
        } else {
            activeCategories.value + cat
        }
    }

    fun clearFilters() {
        query.value = ""
        activeCategories.value = emptySet()
    }

    companion object {
        fun create(): SubstanceScreenViewModel = SubstanceScreenViewModel(JournalRepository.instance)
    }
}
