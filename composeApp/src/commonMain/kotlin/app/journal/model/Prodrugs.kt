package app.journal.model

/**
 * A prodrug whose felt effect is its active metabolite. The ingested compound
 * is inactive or short-lived, so a curve drawn from its own half-life clears
 * too early.
 *
 * Data from Piru (GPL-3.0), data/curated/prodrugs.json, which cites the link
 * per entry; the half-life comes from the metabolite's own catalog row unless
 * the entry carries one.
 */
data class ProdrugLink(
    val prodrug: String,
    val metabolite: String,
    /** Catalog name of the metabolite, so its own half-life can be borrowed. */
    val viaSubstance: String? = null,
    val halfLifeMinutes: Double? = null,
    val citation: String? = null,
    val note: String? = null,
)

object Prodrugs {

    val entries: List<ProdrugLink> = listOf(
        ProdrugLink("Heroin", "6-MAM then morphine", viaSubstance = "Morphine"),
        ProdrugLink("Lisdexamfetamine", "dextroamphetamine", halfLifeMinutes = 581.0, citation = "pmid:18285619"),
        ProdrugLink("Adrafinil", "modafinil", viaSubstance = "Modafinil"),
        ProdrugLink("Psilocybin", "psilocin", viaSubstance = "Psilocin"),
        ProdrugLink("Phendimetrazine", "phenmetrazine", viaSubstance = "Phenmetrazine"),
        ProdrugLink("1cP-LSD", "LSD", viaSubstance = "LSD"),
        ProdrugLink("1P-LSD", "LSD", viaSubstance = "LSD"),
        ProdrugLink("1B-LSD", "LSD", viaSubstance = "LSD"),
        ProdrugLink("ALD-52", "LSD", viaSubstance = "LSD"),
        ProdrugLink("4-AcO-DMT", "psilocin", viaSubstance = "Psilocin"),
        ProdrugLink("4-AcO-DiPT", "4-HO-DiPT", viaSubstance = "4-HO-DiPT"),
        ProdrugLink("Clorazepate", "nordazepam", viaSubstance = "Nordazepam"),
        ProdrugLink("Medazepam", "diazepam", viaSubstance = "Diazepam"),
        ProdrugLink("Primidone", "phenobarbital", viaSubstance = "Phenobarbital"),
    )

    private val byName: Map<String, ProdrugLink> =
        entries.associateBy { normalise(it.prodrug) }

    private fun normalise(name: String): String =
        name.trim().lowercase().replace("-", "").replace(" ", "")

    fun forSubstance(name: String): ProdrugLink? = byName[normalise(name)]

    /**
     * The half-life its effect really follows: the entry's own value first, then
     * the metabolite's catalog row. Null leaves the parent's value in charge.
     */
    fun metaboliteHalfLife(link: ProdrugLink, lookup: (String) -> Double?): Double? =
        link.halfLifeMinutes ?: link.viaSubstance?.let(lookup)
}
