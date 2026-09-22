package app.journal.util

import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiStage

/**
 * Pure duration parsing and formatting helpers, extracted verbatim from
 * the substance detail DurationSection composable so the rules can be
 * unit tested and the screen only renders.
 *
 * Everything here is a pure function over plain model types: no Compose,
 * no repository, no clock.
 */

/** One phase of a duration profile, with its minute range and raw display text. */
data class DurationPhase(
    val label: String,
    val minMinutes: Double,
    val maxMinutes: Double,
    val display: String
)

/**
 * Parse a free-text duration such as "3-5 hours", "45 min" or "2 days"
 * into a (min, max) minute range. Returns null when the text carries no
 * number at all. A single number yields min == max; unknown units (or no
 * unit) are treated as minutes.
 */
fun parseDurationValue(value: String): Pair<Double, Double>? {
    val clean = value.trim()
    val parts = clean.split("\u2013", "-", "\u2013")
    val numPattern = Regex("""([\d.]+)""")
    val unitPattern = Regex("""(minute|minutes|min|hour|hours|hr|day|days)\b""", RegexOption.IGNORE_CASE)

    val nums = numPattern.findAll(clean).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
    val unitMatch = unitPattern.find(clean)
    val unit = unitMatch?.value?.lowercase() ?: ""

    if (nums.isEmpty()) return null

    val min = nums.getOrElse(0) { 0.0 }
    val max = nums.getOrElse(1) { min }
    val multiplier = when {
        unit.startsWith("day") -> 1440.0
        unit.startsWith("hour") || unit.startsWith("hr") -> 60.0
        else -> 1.0
    }

    return Pair(min * multiplier, max * multiplier)
}

/**
 * Parse a whole free-text duration profile (onset, comeup, peak, offset,
 * afterglow) into ordered phases. Unknown keys and unparseable values are
 * skipped; "total" is intentionally not a phase.
 */
fun parseDurationProfile(profile: Map<String, String>): List<DurationPhase> {
    val phaseOrder = listOf("onset", "comeup", "peak", "offset", "afterglow")
    val labels = mapOf(
        "onset" to "Onset", "comeup" to "Comeup", "peak" to "Peak",
        "offset" to "Offset", "afterglow" to "Afterglow", "total" to "Total"
    )

    return phaseOrder.mapNotNull { key ->
        val value = profile[key] ?: return@mapNotNull null
        val parsed = parseDurationValue(value) ?: return@mapNotNull null
        DurationPhase(labels[key] ?: key, parsed.first, parsed.second, value)
    }
}

/**
 * Convert a DoseWikiStage to minutes. Returns null if stage is null or has no data.
 */
fun stageToMinutes(stage: DoseWikiStage?): Pair<Double, Double>? {
    if (stage == null) return null
    val minRaw = stage.min ?: return null
    val maxRaw = stage.max ?: minRaw
    val multiplier = when (stage.unit?.lowercase()) {
        "hours", "hour", "hr" -> 60.0
        "days", "day" -> 1440.0
        else -> 1.0
    }
    return Pair(minRaw * multiplier, maxRaw * multiplier)
}

/**
 * Format a DoseWikiStage as a human-readable display string.
 */
fun formatStage(stage: DoseWikiStage?): String {
    if (stage == null) return ""
    val min = stage.min ?: return ""
    val max = stage.max ?: return "$min ${stage.unit ?: "min"}"
    val unit = stage.unit ?: "min"
    return if (min == max) "$min $unit" else "$min - $max $unit"
}

/** Stage key to phase label mapping. */
val stageLabelMap = mapOf(
    "onset" to "Onset",
    "come_up" to "Comeup",
    "peak" to "Peak",
    "offset" to "Offset",
    "after_effects" to "Afterglow",
    "total_duration" to "Total",
)

/**
 * Parse phases from DoseWiki structured duration data for one route.
 * The stage names map directly to phase labels.
 */
fun parseDoseWikiDuration(
    duration: DoseWikiDuration,
    routeIndex: Int = 0,
): List<DurationPhase> {
    val stages = duration.routes?.getOrNull(routeIndex)?.stages ?: return emptyList()

    val stageKeys = listOf("onset", "come_up", "peak", "offset", "after_effects")
    val phaseResults = mutableListOf<DurationPhase>()

    // Return a helper to get stage by key
    fun stageForKey(key: String): DoseWikiStage? = when (key) {
        "onset" -> stages.onset
        "come_up" -> stages.come_up
        "peak" -> stages.peak
        "offset" -> stages.offset
        "after_effects" -> stages.after_effects
        else -> null
    }

    for (key in stageKeys) {
        val stage = stageForKey(key)
        val parsed = stageToMinutes(stage) ?: continue
        val label = stageLabelMap[key] ?: key
        phaseResults.add(
            DurationPhase(
                label = label,
                minMinutes = parsed.first,
                maxMinutes = parsed.second,
                display = formatStage(stage)
            )
        )
    }

    return phaseResults
}

/**
 * Get the total duration stage from DoseWiki data for one route.
 */
fun getDoseWikiTotal(
    duration: DoseWikiDuration,
    routeIndex: Int = 0,
): Triple<Double?, Double?, String>? {
    val totalStage = duration.routes?.getOrNull(routeIndex)?.stages?.total_duration ?: return null
    val parsed = stageToMinutes(totalStage) ?: return null
    return Triple(parsed.first, parsed.second, formatStage(totalStage))
}
