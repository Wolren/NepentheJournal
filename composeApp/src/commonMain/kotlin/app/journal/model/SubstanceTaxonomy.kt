package app.journal.model

/**
 * Two-level taxonomy for the substances browser.
 *
 * Raw substance classes are a flat jungle (200+ labels mixing effects,
 * chemical families, and singletons). This maps every raw label onto one
 * or more clean broad categories (level one) while keeping the raw labels
 * themselves as the specific level (level two) under their broad parent.
 *
 * Mapping is intentionally many-to-many: a label like
 * "amphetamine (psychedelic)" belongs under both Psychedelics and
 * Stimulants, and a substance matches a broad when ANY of its raw
 * classes maps there. Labels nothing matches fall into Other rather
 * than vanishing from filtered browsing (text search still finds them).
 */
data class BroadCategory(val id: String, val label: String)

object SubstanceTaxonomy {

    val PSYCHEDELICS = BroadCategory("psychedelics", "Psychedelics")
    val ENTACTOGENS = BroadCategory("entactogens", "Entactogens")
    val DISSOCIATIVES = BroadCategory("dissociatives", "Dissociatives")
    val DELIRIANTS = BroadCategory("deliriants", "Deliriants")
    val DYSDELIC = BroadCategory("dysdelic", "Dysdelic")
    val STIMULANTS = BroadCategory("stimulants", "Stimulants")
    val DEPRESSANTS = BroadCategory("depressants", "Depressants")
    val OPIOIDS = BroadCategory("opioids", "Opioids")
    val CANNABINOIDS = BroadCategory("cannabinoids", "Cannabinoids")
    val NOOTROPICS = BroadCategory("nootropics", "Nootropics")
    val MEDICINES = BroadCategory("medicines", "Medicines")
    val NATURAL = BroadCategory("natural", "Supplements and natural")
    val OTHER = BroadCategory("other", "Other")

    /**
     * DoseWiki manual-index category key -> browser broad id. Categories
     * absent here (none currently) are skipped, never forced into Other.
     */
    val CATEGORY_BROADS: Map<String, String> = mapOf(
        "psychedelic" to PSYCHEDELICS.id,
        "dissociative" to DISSOCIATIVES.id,
        "entactogen" to ENTACTOGENS.id,
        "stimulant" to STIMULANTS.id,
        "nootropic" to NOOTROPICS.id,
        "antidepressant" to MEDICINES.id,
        "antipsychotic" to MEDICINES.id,
        "opioid" to OPIOIDS.id,
        "deliriant" to DELIRIANTS.id,
        "hallucinogen" to PSYCHEDELICS.id,
        "gabaergic" to DEPRESSANTS.id,
        "cannabinoid" to CANNABINOIDS.id,
        "miscellaneous" to OTHER.id,
    )
    /** Broads in display order. */
    val broads: List<BroadCategory> = listOf(
        PSYCHEDELICS, ENTACTOGENS, DISSOCIATIVES, DELIRIANTS, DYSDELIC,
        STIMULANTS, DEPRESSANTS, OPIOIDS, CANNABINOIDS,
        NOOTROPICS, MEDICINES, NATURAL, OTHER,
    )

    private fun String.has(vararg needles: String): Boolean =
        needles.any { it in this }

    /**
     * Broad ids for one raw class label. Never empty: unmatched labels
     * report [OTHER]. Input is lowercased defensively; ingest already
     * stores classes lowercase.
     */
    fun broadsOf(rawClass: String?): Set<String> {
        val raw = rawClass?.trim()?.lowercase() ?: return setOf(OTHER.id)
        if (raw.isEmpty()) return setOf(OTHER.id)
        val out = mutableSetOf<String>()

        if (raw.has("cannabinoid", "naphthoyl", "indazole", "benzoyl", "dibenzopyran", "carboxamide")) {
            out += CANNABINOIDS.id
        }
        // Salvinorin scaffolds are KOR agonists by definition.
        if (raw.has("salvinorin")) {
            out += DYSDELIC.id
        }
        // Beta-keto phenethylamines are cathinones, not psychedelics.
        if (!raw.has("keto") && (
            raw.has(
                "psychedelic", "hallucinogen", "tryptamine", "lysergamide",
                "phenethylamine", "phenylpropene", "benzofuran", "benzodifuran", "entheogen",
                "salvinorin", "iboga", "carboline", "diterpene",
            ) || raw in setOf("2c x", "3c x", "dox", "nbome", "nboh", "nbf", "scaline", "mdxx") ||
                (raw.has("indole") && !raw.has("cannabinoid", "naphthoyl", "benzoyl", "carboxamide", "indazole"))
            )
        ) {
            out += PSYCHEDELICS.id
        }
        // Empathogen is the PsychonautWiki spelling of entactogen.
        if (raw.has("entactogen", "empathogen", "mdxx") || raw == "aminoindane") {
            out += ENTACTOGENS.id
        }
        if (raw.has("dissociative", "arylcyclohexyl", "diarylethylamine", "adamantane")) {
            out += DISSOCIATIVES.id
        }
        if (raw.has("deliriant", "tropane")) {
            out += DELIRIANTS.id
        }
        if (raw.has(
            "stimulant", "amphetamine", "cathinone", "keto", "phenidate",
            "pyrrolidino", "pyrrolidine", "xanthine", "pipradrol", "aminorex",
            "naphthylaminopropane", "thiophene", "benzhydryl", "benzhydrol",
            "dephenylmethane", "diphenylmethanethiol", "naphthalene",
            "imidazoline", "oxazoline", "purine", "morpholine",
            "phenylpropylamin",
        ) || raw == "phenylpiperazine" ||
            // Fentanyl scaffolds contain piperidine/piperazine but are opioids.
            (raw.has("piperidine") && !raw.has("anilid", "anilino", "phenylpiper")) ||
            (raw.has("piperazine") && !raw.has("phenylpiper"))
        ) {
            out += STIMULANTS.id
        }
        // Antidepressants contain "depressant" as a substring but are medicines.
        if ((raw.has("depressant") && !raw.has("antidepressant")) || raw.has(
            "sedative", "hypnotic", "anxiolytic", "antihistamine", "ethanolamine",
            "gaba", "diazepine", "barbiturate", "barbituric", "quinazolinone", "carbamate",
            "muscle relaxant", "anticonvulsant", "cyclopyrrolone",
            "pyrazolopyrimidine", "imidazopyridine", "triazolopyridine",
            "butyric acid", "hydroxyisoxazole", "alcohol", "benzoxazine",
        ) || raw == "ether"
        ) {
            out += DEPRESSANTS.id
        }
        if (raw.has(
            "opioid", "morphinan", "anilid", "anilino", "nitazene",
            "diphenylpropylamine", "diphenylheptane", "benzamide", "opium",
            "phenylpiperidine",
        )
        ) {
            out += OPIOIDS.id
        }
        if (raw.has(
            "nootropic", "racetam", "eugeroic", "choline", "modafinil",
            "amino acid", "peptide", "nucleotide", "cysteine", "organic acid",
        )
        ) {
            out += NOOTROPICS.id
        }
        if (raw.has(
            "antidepressant", "antipsychotic", "mood stabilizer", "phenothiazine",
            "butyrophenone", "benzisoxazole",
            "tricyclic", "adrenergic antagonist", "pde 5 inhibitor", "antimalarial",
            "hormone", "steroid", "oxazolinone", "quinoline", "alkali metal",
        ) || (raw.has("azepine") && !raw.has("diazepine"))
        ) {
            out += MEDICINES.id
        }
        if (raw.has("supplement", "oneirogen", "alkaloid", "terpen", "mesembrine")) {
            out += NATURAL.id
        }
        if (out.isEmpty()) out += OTHER.id
        return out
    }

    /** Union of [broadsOf] over all raw classes of a substance. */
    fun broadsOfClasses(classes: List<String>): Set<String> {
        if (classes.isEmpty()) return setOf(OTHER.id)
        return classes.flatMapTo(mutableSetOf()) { broadsOf(it) }
    }

    private val korWord = Regex("\\bkor\\b")

    private fun isKappaTarget(targetName: String?): Boolean {
        val t = targetName?.lowercase() ?: return false
        return "kappa" in t || "oprk" in t || "κ" in t || korWord.containsMatchIn(t)
    }

    private fun isAgonistAction(action: String?): Boolean {
        val a = action?.lowercase() ?: return false
        return "agonist" in a && "antagonist" !in a && "inverse" !in a
    }

    /**
     * True when the IUPHAR interactions include KOR agonism (full, partial,
     * or biased; antagonists and inverse agonists do not count). Feeds the
     * Dysdelic broad, which has no class-label signal.
     */
    fun isKorAgonist(interactions: List<IupharInteraction>): Boolean =
        interactions.any { korActionLabel(it) != null }

    /**
     * Level-two labels for the Dysdelic broad, one per KOR agonist action
     * found (KOR agonist, KOR full agonist, KOR partial agonist,
     * KOR biased agonist).
     */
    fun dysdelicSpecifics(interactions: List<IupharInteraction>): List<String> =
        interactions.mapNotNull { korActionLabel(it) }.distinct()

    private fun korActionLabel(interaction: IupharInteraction): String? {
        if (!isKappaTarget(interaction.targetName)) return null
        val a = interaction.action?.lowercase() ?: return null
        if (!isAgonistAction(a)) return null
        val kind = when {
            "full" in a -> "full agonist"
            "partial" in a -> "partial agonist"
            "biased" in a -> "biased agonist"
            else -> "agonist"
        }
        return "KOR $kind"
    }

    /**
     * All broads for a substance: class-label mapping, curated DoseWiki
     * categories, plus Dysdelic when its IUPHAR data shows KOR agonism or
     * its name marks it as a known KOR agonist without assay rows
     * (the Salvia plant).
     */
    fun broadsFor(
        classes: List<String>,
        interactions: List<IupharInteraction>,
        name: String?,
        curated: List<CuratedSection> = emptyList(),
    ): Set<String> =
        broadsOfClasses(classes) +
            curated.mapNotNull { CATEGORY_BROADS[it.category] } +
            (if (isKorAgonist(interactions) || isKorAgonistByName(name)) setOf(DYSDELIC.id) else emptySet())

    /**
     * Substances known to be KOR agonists that carry no IUPHAR assay rows
     * (plant material and analogues indexed under variant names).
     */
    fun isKorAgonistByName(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return "salvinorin" in n || n == "salvia divinorum"
    }

    /**
     * Level-two labels of one substance under one broad. Curated DoseWiki
     * section labels come first, raw class labels fill the gaps; KOR action
     * labels serve Dysdelic (with a generic KOR agonist label when only
     * the name carries the signal).
     */
    fun specificsFor(
        broadId: String,
        classes: List<String>,
        interactions: List<IupharInteraction>,
        name: String?,
        curated: List<CuratedSection> = emptyList(),
    ): List<String> {
        if (broadId != DYSDELIC.id) {
            val curatedLabels = curated
                .filter { CATEGORY_BROADS[it.category] == broadId }
                .map { it.label }
                .distinct()
            return (curatedLabels + classes.filter { broadId in broadsOf(it) }).distinct()
        }
        val out = (dysdelicSpecifics(interactions) + classes.filter { DYSDELIC.id in broadsOf(it) })
            .distinct()
        return if (out.isEmpty() && isKorAgonistByName(name)) listOf("KOR agonist") else out
    }
}
