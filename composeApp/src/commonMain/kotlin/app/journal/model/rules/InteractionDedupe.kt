package app.journal.model.rules

import app.journal.log.Log
import app.journal.model.Interaction

/**
 * Cross-source duplicate interactions.
 *
 * The seed points some pairs at class placeholder ids (pwiki:lithium, almost
 * never a description) while DoseWiki points the same conceptual pair at real
 * substance ids (dw:lithium, with a reason). Both rows render under the same
 * display name, so the user sees the pair twice, once info-poor.
 *
 * Dedupe groups by sorted lowercase display-name pair and keeps one row per
 * pair: highest severity wins, longest description wins, sources merge. The
 * info-poor row goes invisible. Every collapse is logged so new duplicate
 * patterns surface in diagnostics instead of silently piling up.
 */
object InteractionDedupe {

    fun dedupe(
        interactions: List<Interaction>,
        nameOf: (String) -> String
    ): List<Interaction> {
        if (interactions.size < 2) return interactions
        val groups = interactions.groupBy { row ->
            val names = listOf(
                nameOf(row.substanceAId).lowercase(),
                nameOf(row.substanceBId).lowercase()
            ).sorted()
            names[0] to names[1]
        }
        if (groups.size == interactions.size) return interactions
        var collapsed = 0
        val out = groups.values.map { group ->
            if (group.size == 1) return@map group[0]
            collapsed += group.size - 1
            val winner = group.reduce { acc, row -> richer(acc, row) }
            val bestDescription = group.mapNotNull { row ->
                row.description?.takeIf { it.isNotBlank() }
            }.maxByOrNull { it.length }
            val mergedSources = group.flatMap { it.sources }.distinct()
            if (winner.description == bestDescription && winner.sources == mergedSources) winner
            else winner.copy(description = bestDescription, sources = mergedSources)
        }
        Log.withTag("InteractionDedupe").w {
            "Collapsed $collapsed duplicate interaction rows into ${out.size} pairs"
        }
        return out
    }

    /**
     * Richness comparison for rows covering the same pair: severity first,
     * then info content, then source count. Deterministic: ties keep [a].
     */
    fun richer(a: Interaction, b: Interaction): Interaction {
        val byRisk = a.riskLevel.ordinal.compareTo(b.riskLevel.ordinal)
        if (byRisk != 0) return if (byRisk < 0) a else b
        val aLen = a.description?.length ?: -1
        val bLen = b.description?.length ?: -1
        if (aLen != bLen) return if (aLen > bLen) a else b
        return if (a.sources.size >= b.sources.size) a else b
    }
}
