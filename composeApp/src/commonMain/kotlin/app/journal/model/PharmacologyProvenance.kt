package app.journal.model

/**
 * The evidence behind one pharmacology row: a PubMed id, a free-text reference,
 * or a DOI. Sources name the same paper differently, so only these three count.
 */
data class Citation(
    val pmid: String? = null,
    val text: String? = null,
    val doi: String? = null,
) {
    val url: String?
        get() = pmid?.let { "https://pubmed.ncbi.nlm.nih.gov/$it/" }
            ?: doi?.let { "https://doi.org/$it" }
            ?: text?.trim()?.takeIf { it.startsWith("http") }

    /** What the link opens, named the way a reader cites it. */
    val label: String
        get() = when {
            pmid != null -> "PubMed $pmid"
            doi != null -> "DOI $doi"
            !text.isNullOrBlank() -> text.trim()
            else -> ""
        }

    val isLink: Boolean get() = url != null
}

/** One source's own row for a target, kept so the app can show the spread. */
data class PharmacologyContribution(
    val source: String,
    val type: String,
    val nanoMolar: Double?,
    val action: String? = null,
    val citation: Citation? = null,
)

/** Source names in the order the user ranked them, unknown names last. */
fun sortBySourcePriority(
    contributions: List<PharmacologyContribution>,
    priority: List<String>,
): List<PharmacologyContribution> {
    val rank = priority.withIndex().associate { it.value to it.index }
    return contributions.sortedWith(
        compareBy({ rank[it.source] ?: Int.MAX_VALUE }, { it.type }),
    )
}

/** Sources that disagree on a value, as the smallest and largest measurement. */
fun spreadOf(contributions: List<PharmacologyContribution>): Pair<Double, Double>? {
    val values = contributions.mapNotNull { it.nanoMolar }
    if (values.size < 2) return null
    return values.min() to values.max()
}
