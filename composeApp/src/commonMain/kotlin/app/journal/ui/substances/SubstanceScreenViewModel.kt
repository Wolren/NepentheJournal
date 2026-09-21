package app.journal.ui.substances

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Substance
import app.journal.model.SubstanceTaxonomy
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

/** Broad category option with the number of matching substances. */
data class BroadOption(val id: String, val label: String, val count: Int)

/** Specific (raw class) option under the active broad, with match count. */
data class SpecificOption(val label: String, val count: Int)

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

    /**
     * Shared taxonomy annotation: broad and specific classes are computed once
     * per substance here, so broad options, specific options, and filtered
     * results all reuse the same pass instead of mapping the taxonomy 3x per
     * keystroke.
     */
    private data class AnnotatedSubstance(
        val substance: Substance,
        val broads: Set<String>,
        val specificsByBroad: Map<String, List<String>>,
    )

    private val annotatedSubstances: Flow<List<AnnotatedSubstance>> = realSubstances.map { list ->
        list.map { sub ->
            val broads = vmBroads(sub)
            AnnotatedSubstance(
                substance = sub,
                broads = broads,
                specificsByBroad = broads.associateWith { broad -> vmSpecifics(sub, broad) },
            )
        }
    }

    /**
     * Level one: broad categories present in the data, in taxonomy order,
     * each with its substance count.
     */
    val broadOptions: Flow<List<BroadOption>> = annotatedSubstances.map { list ->
        val counts = mutableMapOf<String, Int>()
        list.forEach { annotated ->
            annotated.broads.forEach { id ->
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        SubstanceTaxonomy.broads.mapNotNull { broad ->
            counts[broad.id]?.let { BroadOption(broad.id, broad.label, it) }
        }
    }

    /** Active level-one broad filter, or null for all substances. */
    val activeBroad = MutableStateFlow<String?>(null)

    /**
     * Level two: raw class labels under the active broad, most common
     * first, each with its count within the broad.
     */
    val specificOptions: Flow<List<SpecificOption>> =
        combine(annotatedSubstances, activeBroad) { list, broad ->
            if (broad == null) return@combine emptyList()
            list.asSequence()
                .filter { broad in it.broads }
                .flatMap { it.specificsByBroad[broad].orEmpty() }
                .groupingBy { it }.eachCount()
                .map { (label, count) -> SpecificOption(label, count) }
                .sortedByDescending { it.count }
        }

    /** Active level-two refinements within the active broad. */
    val activeSpecifics = MutableStateFlow<Set<String>>(emptySet())

    /** Current search query. */
    val query = MutableStateFlow("")

    /** Precomputed dose stats from repository. */
    val substanceDoseStats: Map<String, Pair<Int, Long>>
        get() = repo.substanceDoseStats

    /** Filtered + searched results; the query is debounced so typing recomputes once per pause. */
    @OptIn(FlowPreview::class)
    val results: Flow<List<Substance>> = combine(
        annotatedSubstances, query.debounce(250.milliseconds), activeBroad, activeSpecifics
    ) { annotated, q, broad, specs ->
        var result = annotated

        // Broad filter: a substance matches when ANY of its classes maps there
        // (or its IUPHAR data shows KOR agonism for Dysdelic).
        if (broad != null) {
            result = result.filter { item ->
                broad in item.broads
            }
            // Specific refinement within the broad.
            if (specs.isNotEmpty()) {
                result = result.filter { item ->
                    item.specificsByBroad[broad].orEmpty().any { it in specs }
                }
            }
        }

        // Text search
        if (q.isNotBlank()) {
            val lq = q.lowercase()
            result = result.filter { item ->
                val sub = item.substance
                sub.name.lowercase().contains(lq) ||
                sub.aliases.any { it.lowercase().contains(lq) } ||
                sub.substanceClass.any { it.lowercase().contains(lq) }
            }
        }

        result.map { it.substance }
    }

    fun selectBroad(id: String?) {
        if (activeBroad.value != id) {
            activeBroad.value = id
            activeSpecifics.value = emptySet()
        }
    }

    fun toggleSpecific(label: String) {
        activeSpecifics.value = if (label in activeSpecifics.value) {
            activeSpecifics.value - label
        } else {
            activeSpecifics.value + label
        }
    }

    /** All broads for one substance, including Dysdelic from IUPHAR data. */
    private fun vmBroads(sub: Substance): Set<String> =
        SubstanceTaxonomy.broadsFor(
            sub.substanceClass,
            sub.iupharData?.interactions ?: emptyList(),
            sub.name,
            sub.curatedSections,
        )

    /** Level-two labels of one substance under one broad. */
    private fun vmSpecifics(sub: Substance, broad: String): List<String> =
        SubstanceTaxonomy.specificsFor(
            broad,
            sub.substanceClass,
            sub.iupharData?.interactions ?: emptyList(),
            sub.name,
            sub.curatedSections,
        )

    fun clearFilters() {
        query.value = ""
        activeBroad.value = null
        activeSpecifics.value = emptySet()
    }

    companion object {
        fun create(): SubstanceScreenViewModel = SubstanceScreenViewModel(JournalRepository.instance)
    }
}
