package app.journal.model

/**
 * Ranks substances for a typed query so the compound a user names comes
 * before everything that merely contains the same letters.
 *
 * Substring filtering alone put "1B-LSD" and "1P-LSD" above "LSD" on an
 * alphabetical list, which reads as the main compound being buried under its
 * analogs. The rank is a small integer: lower sorts first, null means the
 * substance does not match at all.
 *
 * Tiers, in order:
 * 0  name is exactly the query
 * 10 name starts with the query
 * 20 an alias or class is exactly the query
 * 30 a later word of the name starts with the query
 * 40 an alias or class starts with the query
 * 50 the name contains the query
 * 60 an alias or class contains the query
 *
 * Ties fall back to popularity tier, then shorter name, then alphabetical, so
 * the result is stable and never depends on repository order. Popularity lives
 * in [SubstancePopularity] and only breaks ties: a better match always wins.
 */
object SubstanceSearch {

    /** Rank of one substance, or null when no field matches [query]. */
    fun score(
        query: String,
        name: String,
        aliases: List<String> = emptyList(),
        classes: List<String> = emptyList(),
    ): Int? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        return scoreLower(
            needle,
            name.lowercase(),
            aliases.map { it.lowercase() },
            classes.map { it.lowercase() },
        )
    }

    /**
     * [score] for fields the caller already lowercased. The substances screen
     * keeps its fields lowercase per data change, so a keystroke allocates
     * nothing per row.
     */
    fun scoreLower(
        needle: String,
        nameLower: String,
        aliasesLower: List<String>,
        classesLower: List<String>,
    ): Int? {
        if (needle.isEmpty()) return null
        if (nameLower == needle) return 0
        if (nameLower.startsWith(needle)) return 10
        for (alias in aliasesLower) if (alias == needle) return 20
        for (label in classesLower) if (label == needle) return 20
        if (containsWordStart(nameLower, needle)) return 30
        for (alias in aliasesLower) if (alias.startsWith(needle)) return 40
        for (label in classesLower) if (label.startsWith(needle)) return 40
        if (nameLower.contains(needle)) return 50
        for (alias in aliasesLower) if (alias.contains(needle)) return 60
        for (label in classesLower) if (label.contains(needle)) return 60
        return null
    }

    /**
     * Filters and ranks [substances] for [query]. An empty query keeps the
     * incoming order; that is the browse list, not a search.
     */
    fun rank(substances: List<Substance>, query: String): List<Substance> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return substances
        return substances
            .mapNotNull { substance ->
                score(
                    query = needle,
                    name = substance.name,
                    aliases = substance.aliases,
                    classes = substance.substanceClass,
                )?.let { substance to it }
            }
            .sortedWith(
                compareBy(
                    { (_, rank) -> rank },
                    { (substance, _) -> SubstancePopularity.tierOf(substance.name) },
                    { (substance, _) -> substance.name.length },
                    { (substance, _) -> substance.name.lowercase() },
                )
            )
            .map { it.first }
    }

    /** True when [needle] starts a word inside [text] that is not the first word. */
    private fun containsWordStart(text: String, needle: String): Boolean {
        var from = 1
        while (true) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return false
            if (!text[hit - 1].isLetterOrDigit()) return true
            from = hit + 1
        }
    }
}
