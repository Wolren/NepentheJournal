package app.journal.model

/** A conversion the data refuses, with the reason the source gives. */
data class RefusedConversion(val drug: String, val reason: String)

/**
 * Dose equivalence inside a drug class, so a logged dose can answer "how much
 * alprazolam is my 10 mg diazepam".
 *
 * Benzodiazepine bands come from the Ashton Manual, table 1, as milligrams
 * approximately equivalent to 10 mg diazepam. Opioid factors come from the CDC
 * Clinical Practice Guideline 2022, oral morphine milligram equivalents.
 * Both are approximations; the bands stay bands.
 */
object DoseEquivalence {

    const val ASHTON_CITATION =
        "Ashton CH. Benzodiazepines: How They Work and How to Withdraw, 2002, table 1."
    const val CDC_CITATION =
        "Dowell D et al. CDC Clinical Practice Guideline for Prescribing Opioids for Pain, MMWR 2022;71(RR-3)."

    /** Milligrams of the drug that approximate 10 mg diazepam: low to high. */
    val benzodiazepines: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "Alprazolam" to 0.5..0.5,
        "Bromazepam" to 5.0..6.0,
        "Chlordiazepoxide" to 25.0..25.0,
        "Clobazam" to 20.0..20.0,
        "Clonazepam" to 0.5..0.5,
        "Clorazepate" to 15.0..15.0,
        "Diazepam" to 10.0..10.0,
        "Estazolam" to 1.0..2.0,
        "Flunitrazepam" to 1.0..1.0,
        "Flurazepam" to 15.0..30.0,
        "Halazepam" to 20.0..20.0,
        "Ketazolam" to 15.0..30.0,
        "Loprazolam" to 1.0..2.0,
        "Lorazepam" to 1.0..1.0,
        "Lormetazepam" to 1.0..2.0,
        "Medazepam" to 10.0..10.0,
        "Nitrazepam" to 10.0..10.0,
        "Nordazepam" to 10.0..10.0,
        "Oxazepam" to 20.0..20.0,
        "Prazepam" to 10.0..20.0,
        "Quazepam" to 20.0..20.0,
        "Temazepam" to 20.0..20.0,
        "Triazolam" to 0.5..0.5,
    )

    /** Milligram of oral morphine equivalent per milligram. */
    val opioidMme: Map<String, Double> = mapOf(
        "Morphine" to 1.0,
        "Codeine" to 0.15,
        "Hydrocodone" to 1.0,
        "Oxycodone" to 1.5,
        "Oxymorphone" to 3.0,
        "Hydromorphone" to 5.0,
        "Tramadol" to 0.2,
        "Tapentadol" to 0.4,
    )

    /** Opioids the data declines to convert, so the card can explain instead of guessing. */
    val refusals: Map<String, String> = mapOf(
        "Methadone" to "Half-life is long and variable, so a converted dose can read safe while the risk still builds.",
        "Fentanyl" to "Dosed in mcg/hr, which is a rate, so it shares no unit with the mg rows.",
        "Buprenorphine" to "Partial agonist with a ceiling, which CDC excludes from total MME.",
    )

    val refusalList: List<RefusedConversion> = refusals.map { RefusedConversion(it.key, it.value) }

    /**
     * Diazepam equivalent of [milligrams] of [drug], as a band because Ashton
     * gives a range for seven drugs. Null when the drug is not in the table.
     */
    fun diazepamEquivalent(drug: String, milligrams: Double): ClosedFloatingPointRange<Double>? {
        val band = benzodiazepines[drug] ?: return null
        if (milligrams <= 0.0) return null
        val low = milligrams * 10.0 / band.endInclusive
        val high = milligrams * 10.0 / band.start
        return low..high
    }

    /** Milligrams of [to] that match [milligrams] of [from], with the same band. */
    fun convertBenzodiazepine(from: String, to: String, milligrams: Double): ClosedFloatingPointRange<Double>? {
        val target = benzodiazepines[to] ?: return null
        val diazepam = diazepamEquivalent(from, milligrams) ?: return null
        val low = diazepam.start * target.start / 10.0
        val high = diazepam.endInclusive * target.endInclusive / 10.0
        return low..high
    }

    fun morphineEquivalent(drug: String, milligrams: Double): Double? {
        val factor = opioidMme[drug] ?: return null
        if (milligrams <= 0.0) return null
        return milligrams * factor
    }

    fun convertOpioid(from: String, to: String, milligrams: Double): Double? {
        val mme = morphineEquivalent(from, milligrams) ?: return null
        val target = opioidMme[to] ?: return null
        return mme / target
    }

    fun refusalFor(drug: String): String? = refusals[drug]
}
