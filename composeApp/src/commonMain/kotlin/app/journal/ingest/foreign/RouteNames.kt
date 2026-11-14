package app.journal.ingest.foreign

/** Route and unit spellings shared by every foreign importer. */
internal object RouteNames {

    private val aliases = mapOf(
        "oral" to "oral",
        "po" to "oral",
        "by mouth" to "oral",
        "sublingual" to "sublingual",
        "sl" to "sublingual",
        "buccal" to "buccal",
        "insufflated" to "insufflated",
        "insufflation" to "insufflated",
        "intranasal" to "insufflated",
        "nasal" to "insufflated",
        "snorted" to "insufflated",
        "inhaled" to "inhaled",
        "inhalation" to "inhaled",
        "smoked" to "smoked",
        "smoking" to "smoked",
        "vaporized" to "vaporized",
        "vaporised" to "vaporized",
        "vaped" to "vaporized",
        "intravenous" to "intravenous",
        "iv" to "intravenous",
        "intramuscular" to "intramuscular",
        "im" to "intramuscular",
        "subcutaneous" to "subcutaneous",
        "sc" to "subcutaneous",
        "transdermal" to "transdermal",
        "rectal" to "rectal",
    )

    /** Lowercase route name the app already colors; unknown spellings pass through lowercased. */
    fun normalize(raw: String?): String {
        val key = raw?.trim()?.lowercase()?.replace('_', ' ')?.replace('-', ' ')?.trim()
        if (key.isNullOrEmpty()) return "oral"
        return aliases[key] ?: key
    }

    /** Blank units become mg; microgram spellings collapse to µg. */
    fun unit(raw: String?): String {
        val unit = raw?.trim()?.lowercase().orEmpty()
        return when {
            unit.isEmpty() -> "mg"
            unit == "ug" || unit == "mcg" || unit == "μg" -> "µg"
            else -> unit
        }
    }
}
