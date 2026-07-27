package app.journal.data

import app.journal.model.Interaction
import app.journal.model.InteractionRisk

/**
 * Results of checking a set of substance IDs for known interactions.
 */
data class InteractionCheckResult(
    val dangerous: List<Pair<String, String>> = emptyList(),
    val unsafe: List<Pair<String, String>> = emptyList(),
    val uncertain: List<Pair<String, String>> = emptyList()
) {
    val hasIssues: Boolean get() = dangerous.isNotEmpty() || unsafe.isNotEmpty()
    val totalCount: Int get() = dangerous.size + unsafe.size + uncertain.size
}

/**
 * Checks pairwise interactions using an indexed map for O(1) lookups.
 *
 * The index is cached across calls: if the interaction list content hasn't
 * changed (checked by hashCode), the cached index is reused instead of
 * rebuilding it from scratch. This avoids O(N) index rebuild on every
 * composable recomposition when the same interaction list is used.
 */
object InteractionChecker {

    // ---- Cached index ----
    private var cachedInteractionHash: Int = 0
    private var cachedIndex: Map<InteractionKey, Interaction> = emptyMap()

    private fun indexInteractions(allInteractions: List<Interaction>): Map<InteractionKey, Interaction> {
        val hash = allInteractions.hashCode()
        if (hash == cachedInteractionHash && cachedIndex.isNotEmpty()) {
            return cachedIndex
        }
        val map = mutableMapOf<InteractionKey, Interaction>()
        for (interaction in allInteractions) {
            val key = InteractionKey.of(interaction.substanceAId, interaction.substanceBId)
            if (key !in map) map[key] = interaction
        }
        cachedInteractionHash = hash
        cachedIndex = map
        return map
    }

    /**
     * Check all substance IDs in [ids] against each other for known interactions.
     */
    fun checkPairwise(
        ids: List<String>,
        allInteractions: List<Interaction>
    ): InteractionCheckResult {
        if (ids.size < 2) return InteractionCheckResult()
        val index = indexInteractions(allInteractions)
        val idSet = ids.toSet()
        val idList = idSet.toList()
        val dangerous = mutableListOf<Pair<String, String>>()
        val unsafe = mutableListOf<Pair<String, String>>()
        val uncertain = mutableListOf<Pair<String, String>>()
        scanPairs(idList) { _, a, _, b ->
            val key = InteractionKey.of(a, b)
            val interaction = index[key]
            if (interaction != null) {
                when (interaction.riskLevel) {
                    InteractionRisk.DANGEROUS -> dangerous.add(a to b)
                    InteractionRisk.UNSAFE -> unsafe.add(a to b)
                    else -> uncertain.add(a to b)
                }
            }
            null
        }
        return InteractionCheckResult(dangerous, unsafe, uncertain)
    }

    /**
     * Check a single new substance against an existing set.
     */
    fun checkAgainstExisting(
        newId: String,
        existingIds: List<String>,
        allInteractions: List<Interaction>
    ): InteractionCheckResult {
        val index = indexInteractions(allInteractions)
        val dangerous = mutableListOf<Pair<String, String>>()
        val unsafe = mutableListOf<Pair<String, String>>()
        val uncertain = mutableListOf<Pair<String, String>>()

        for (existing in existingIds) {
            if (existing == newId) continue
            val key = InteractionKey.of(newId, existing)
            val interaction = index[key]

            if (interaction != null) {
                when (interaction.riskLevel) {
                    InteractionRisk.DANGEROUS -> dangerous.add(newId to existing)
                    InteractionRisk.UNSAFE -> unsafe.add(newId to existing)
                    else -> uncertain.add(newId to existing)
                }
            }
        }

        return InteractionCheckResult(dangerous, unsafe, uncertain)
    }
}
