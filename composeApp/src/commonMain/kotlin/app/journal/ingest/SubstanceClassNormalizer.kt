package app.journal.ingest

/**
 * Normalizes substance class labels: deduplicates, handles plural/case variants,
 * splits joined chemical+psychoactive compound strings, and filters noise.
 */
object SubstanceClassNormalizer {

    private val canonical: Map<String, String> = buildMap {
        // Chem/psycho class variants -> canonical lowercase form
        put("psychedelic", "psychedelic"); put("psychedelics", "psychedelic")
        put("stimulant", "stimulant"); put("stimulants", "stimulant")
        put("depressant", "depressant"); put("depressants", "depressant")
        put("opioid", "opioid"); put("opioids", "opioid")
        put("dissociative", "dissociative"); put("dissociatives", "dissociative")
        put("benzodiazepine", "benzodiazepine"); put("benzodiazepines", "benzodiazepine")
        put("barbiturate", "barbiturate"); put("barbiturates", "barbiturate")
        put("cannabinoid", "cannabinoid"); put("cannabinoids", "cannabinoid")
        put("nootropic", "nootropic"); put("nootropics", "nootropic")
        put("deliriant", "deliriant"); put("deliariants", "deliriant")
        put("hallucinogen", "hallucinogen"); put("hallucinogens", "hallucinogen")
        put("sedative", "sedative"); put("sedatives", "sedative")
        put("anesthetic", "anesthetic"); put("anesthetics", "anesthetic")
        put("antidepressant", "antidepressant"); put("antidepressants", "antidepressant")
        put("antipsychotic", "antipsychotic"); put("antipsychotics", "antipsychotic")
        put("anxiolytic", "anxiolytic"); put("anxiolytics", "anxiolytic")
        put("antihistamine", "antihistamine"); put("antihistamines", "antihistamine")
        put("eugeroic", "eugeroic"); put("eugeroics", "eugeroic")
        put("euphoriant", "euphoriant"); put("euphoriants", "euphoriant")
        put("hypnotic", "hypnotic"); put("hypnotics", "hypnotic")
        put("hallucinogen", "hallucinogen"); put("hallucinogens", "hallucinogen")
        // empathogen / entactogen are used interchangeably in PW data
        put("empathogen", "entactogen"); put("empathogens", "entactogen")
        put("entactogen", "entactogen"); put("entactogens", "entactogen")
        put("supplement", "supplement"); put("supplements", "supplement")
    }

    /** Labels that are metadata/confidence markers, not substance classes. */
    private val noise = setOf("common", "tentative", "experimental", "unconfirmed")

    /**
     * Normalize a list of raw class labels.
     * 1. Split any comma-joined compounds (from old ingestors)
     * 2. Trim and lowercase
     * 3. Map plural/variant to canonical
     * 4. Remove noise labels
     * 5. Replace separators (_ -) with spaces
     * 6. Deduplicate and sort
     */
    fun normalize(classes: List<String>): List<String> {
        return classes
            .flatMap { raw -> raw.split(", ") }
            .map { it.trim().lowercase() }
            .map { canonical[it] ?: it }
            .filter { it !in noise && it.isNotBlank() }
            .map { it.replace('_', ' ').replace('-', ' ') }
            .distinct()
            .sorted()
    }
}
