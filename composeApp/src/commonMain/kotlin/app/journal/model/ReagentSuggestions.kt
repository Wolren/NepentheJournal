package app.journal.model

/**
 * Class-based reagent suggestions for the Drug Testing card on the
 * substance detail screen.
 *
 * General principles only: reagent names are informational, no medical
 * claims. Empty for classes where reagent testing is not applicable
 * (e.g. cannabis).
 */
fun reagentSuggestions(substance: Substance): List<String> {
    val cls = substance.substanceClass.joinToString(" ").lowercase()
    return when {
        cls.contains("tryptamine") || cls.contains("lysergamide") -> listOf("Ehrlich", "Marquis", "Mecke")
        cls.contains("phenethylamine") -> listOf("Marquis", "Mecke", "Froehde", "Simon's")
        cls.contains("psychedelic") || cls.contains("hallucinogen") -> listOf("Ehrlich", "Marquis", "Mecke")
        cls.contains("empathogen") || cls.contains("entactogen") -> listOf("Marquis", "Simon's", "Mecke", "Froehde")
        cls.contains("stimulant") -> listOf("Marquis", "Mecke", "Simon's")
        cls.contains("opioid") || cls.contains("opiate") -> listOf("Marquis", "Mecke")
        cls.contains("dissociative") -> listOf("Mecke", "Marquis")
        cls.contains("benzodiazepine") || cls.contains("z-drug") -> listOf("Marquis")
        cls.contains("cathinone") -> listOf("Marquis", "Mecke", "Simon's")
        cls.contains("cannabinoid") || cls.contains("cannabis") -> emptyList()
        else -> listOf("Marquis", "Mecke", "Froehde")
    }
}
