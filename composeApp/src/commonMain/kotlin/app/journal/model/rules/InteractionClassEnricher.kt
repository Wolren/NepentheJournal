package app.journal.model.rules

import app.journal.model.Substance

/**
 * Restores interaction classes the DoseWiki ingest drops, because the upstream
 * `psychoactive_class` labels name drug families the rule table does not speak.
 * Additive only: [enrich] never removes a class, so a second pass changes nothing.
 */
object InteractionClassEnricher {

    private val SSRI_NAMES = setOf(
        "sertraline", "citalopram", "escitalopram", "fluoxetine", "paroxetine",
        "fluvoxamine", "vortioxetine", "vilazodone",
    )
    private val MAOI_NAMES = setOf(
        "phenelzine", "tranylcypromine", "isocarboxazid", "moclobemide",
        "selegiline", "harmine", "harmaline",
    )
    private val RELEASER_NAMES = setOf(
        "mdma", "mda", "mdea", "methylone", "mephedrone", "ethylone", "butylone",
        "3-mmc", "4-mmc", "4-fa", "4-fluoroamphetamine", "5-mapb", "6-apb",
    )
    private val SEROTONERGIC_NAMES = setOf(
        "tramadol", "tapentadol", "meperidine", "pethidine", "dextromethorphan",
        "trazodone", "mirtazapine", "venlafaxine", "duloxetine",
    )

    /** Classes this substance should carry but does not; empty when already complete. */
    fun missingClasses(substance: Substance): List<String> {
        val name = substance.name.trim().lowercase()
        val labels = substance.substanceClass.map { it.trim().lowercase() }
        val derived = linkedSetOf<String>()
        if (name in SSRI_NAMES) {
            derived += "ssri"
            derived += "serotonergic"
        }
        if (name in MAOI_NAMES) derived += "maoi"
        if (name.startsWith("lithium")) derived += "lithium"
        if (name in RELEASER_NAMES || labels.any { it.startsWith("entactogen") }) {
            derived += "serotonin_releaser"
        }
        if (name in SEROTONERGIC_NAMES) derived += "serotonergic"
        if (labels.any { it.contains("benzodiazepine") }) derived += "benzodiazepine"
        val present = substance.interactionClasses.mapTo(mutableSetOf()) { it.lowercase() }
        return derived.filter { it !in present }
    }

    fun enrich(substance: Substance): Substance {
        val missing = missingClasses(substance)
        if (missing.isEmpty()) return substance
        return substance.copy(interactionClasses = (substance.interactionClasses + missing).distinct())
    }
}
