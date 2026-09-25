package app.journal.util

/**
 * Pure helpers for reading and rendering dose magnitudes.
 *
 * Three jobs, all free of Compose / repository / clock so they can be pinned
 * from commonTest:
 *  - parse the free-text dosage bands a Substance carries ("75-150 µg"),
 *  - normalise a dose unit so a band can be compared against a logged dose,
 *  - format an amount for UI text without "2400.0" noise.
 */

/** One parsed dosage band, e.g. "75-150 µg" -> min 75.0, max 150.0, unit "µg". */
data class ParsedDoseBand(val min: Double, val max: Double, val unit: String)

private val numberPattern = Regex("""\d+(?:\.\d+)?""")
private val trailingUnitPattern = Regex("""([A-Za-zµμ]+)\s*$""")

/**
 * Parse a dosage band string. Ranges ("1-2.5 mL", "0.5-1 mL") and single
 * values ("300 mg") both work; a band with no number at all yields null.
 * The unit is the trailing letter run, and is empty when the band carries
 * no unit.
 */
fun parseDoseBand(value: String): ParsedDoseBand? {
    val numbers = numberPattern.findAll(value)
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()
    if (numbers.isEmpty()) return null
    val min = numbers.first()
    val max = numbers.getOrElse(1) { min }
    val unit = trailingUnitPattern.find(value.trim())?.groupValues?.get(1) ?: ""
    return ParsedDoseBand(min, max, unit)
}

/**
 * Canonical spelling of a dose unit for comparison: "µg", "μg" and "mcg"
 * all become "ug", casing and spaces are dropped. Two doses share a unit
 * only when their normalised forms are equal - "mg" never equals "g".
 */
fun normalizeDoseUnit(unit: String): String =
    unit.trim()
        .lowercase()
        .replace('µ', 'u')   // micro sign U+00B5
        .replace('μ', 'u')   // Greek small letter mu U+03BC
        .replace("mcg", "ug")
        .replace(" ", "")

/** Preference order: the typical dose describes a "normal" amount best. */
private val referenceBandOrder = listOf("common", "strong", "light", "threshold")

/**
 * Reference amount for a dot meter: the top of the band that best describes a
 * typical dose, but only when that band is expressed in [unit]. Returns null
 * when the substance carries no bands for this unit, so callers can fall back
 * to plain numbers instead of drawing a meter against a made-up scale.
 */
fun referenceDoseAmount(bands: Map<String, String>, unit: String): Double? {
    val wanted = normalizeDoseUnit(unit)
    if (wanted.isEmpty()) return null
    for (key in referenceBandOrder) {
        val raw = bands[key] ?: continue
        val parsed = parseDoseBand(raw) ?: continue
        if (parsed.max <= 0.0) continue
        if (normalizeDoseUnit(parsed.unit) == wanted) return parsed.max
    }
    return null
}

/**
 * Amount for display: whole numbers keep no decimals ("2400", "75"), anything
 * else keeps at most two ("2.5", "0.67"). Never emits ".0" tails.
 */
fun formatDoseAmount(amount: Double): String {
    if (amount.isNaN() || amount.isInfinite()) return ""
    val rounded = kotlin.math.round(amount * 100.0) / 100.0
    return if (rounded == kotlin.math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
