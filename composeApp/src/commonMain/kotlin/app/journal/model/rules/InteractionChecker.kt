package app.journal.model.rules

import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.log.Log

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
    private var cachedBuilt: Boolean = false
    /** Source list the cache was built from (identity fast path, see below). */
    private var cachedSource: List<Interaction>? = null
    private var cachedInteractionHash: Int = 0
    private var cachedIndex: Map<InteractionKey, Interaction> = emptyMap()

    /** Counts index rebuilds; test observability for cache-hit assertions. */
    internal var rebuildCount: Int = 0
        private set

    private fun indexInteractions(allInteractions: List<Interaction>): Map<InteractionKey, Interaction> {
        if (cachedBuilt && allInteractions === cachedSource) {
            // Wave4 hash-cost fix: the documented hot path (composable
            // recomposition) passes the SAME StateFlow list instance until a
            // mutation replaces it, so identity alone proves content equality
            // and the O(n) List.hashCode() is skipped entirely. No behavior
            // change: an identical instance cannot have different content.
            // The reference is dropped on every rebuild, so at most the
            // current and previous list stay reachable through this object.
            return cachedIndex
        }
        // Content proxy for a DIFFERENT list instance: Kotlin's List carries
        // no revision/epoch token, so one O(n) hashCode() is the only cheap
        // way to ask "did the content change?" without an O(n) element-wise
        // compare (which would cost exactly as much as the hash). Hashing is
        // therefore kept here deliberately; a true hash collision would serve
        // the stale index, the same accepted residual as before wave4.
        val hash = allInteractions.hashCode()
        if (cachedBuilt && hash == cachedInteractionHash) {
            cachedSource = allInteractions
            return cachedIndex
        }
        val map = mutableMapOf<InteractionKey, Interaction>()
        for (interaction in allInteractions) {
            val key = InteractionKey.of(interaction.substanceAId, interaction.substanceBId)
            val prev = map[key]
            if (prev == null) {
                map[key] = interaction
            } else {
                // Same id pair from two sources: keep the richer row and say
                // so, instead of silently keeping whichever arrived first.
                val winner = InteractionDedupe.richer(prev, interaction)
                if (winner !== prev) {
                    map[key] = winner
                    Log.withTag("InteractionChecker").w {
                        "Replacing info-poor duplicate interaction ${interaction.id}"
                    }
                }
            }
        }
        cachedInteractionHash = hash
        cachedSource = allInteractions
        cachedIndex = map
        cachedBuilt = true
        rebuildCount++
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
