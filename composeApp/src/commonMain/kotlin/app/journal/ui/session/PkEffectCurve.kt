package app.journal.ui.session

import app.journal.data.PkCurve
import app.journal.model.Dose
import app.journal.model.Substance
import app.journal.util.parseDurationProfile
import kotlin.math.roundToInt

/**
 * A derived PK curve for one substance, plus what it was derived from so the
 * card can say whether the half-life came from data or from the effect window.
 */
data class PkEffectCurve(
    val samples: List<EffectSample>,
    val halfLifeMinutes: Double,
    val halfLifeFromSource: Boolean,
)

/**
 * Curves one substance's doses as summed first-order absorption/elimination,
 * the way Piru does. Height is in the app-wide 0..10 intensity scale, scaled
 * by dose against the substance's moderate band, so 20 mg and 300 mg differ
 * and a redose adds a second rise. Returns null when no half-life can be
 * established, leaving the caller to fall back to the duration profile.
 */
fun pkEffectSamples(
    doses: List<Dose>,
    substance: Substance,
    startMs: Long,
    endMs: Long,
): PkEffectCurve? {
    if (doses.isEmpty()) return null
    val timed = doses
        .mapNotNull { dose -> doseAmountMg(dose.amount, dose.unit)?.let { it to dose.timestamp } }
        .sortedBy { it.second }
    if (timed.isEmpty()) return null

    val referenceMg = referenceDoseMg(substance, timed.maxOf { it.first }) ?: return null
    val phases = parseDurationProfile(substance.durationProfile)
    // parseDurationProfile drops the total stage, and that stage is the real window end.
    val totalMinutes = substance.durationProfile["total"]
        ?.let { app.journal.util.parseDurationValue(it) }
        ?.let { (it.first + it.second) / 2.0 }
        ?: totalDurationMinutes(phases)
    val tmaxFromData = substance.tmaxMinutes
    val halfLifeFromSource = substance.halfLifeMinutes != null
    val halfLife = substance.halfLifeMinutes
        ?: totalMinutes?.let { total -> PkCurve.estimateHalfLifeMinutes(total, tmaxFromData ?: total * 0.25) }
        ?: return null
    val tmax = (tmaxFromData ?: halfLife * 0.35).coerceAtLeast(1.0)

    val ke = PkCurve.eliminationRate(halfLife)
    val ka = PkCurve.absorptionRate(ke, tmax)
    val step = ((endMs - startMs) / 240L).coerceIn(60_000L, 900_000L)
    val series = PkCurve.series(
        doses = timed.map { (mg, timestamp) -> timestamp to mg / referenceMg },
        ke = ke,
        ka = ka,
        startMs = startMs,
        endMs = endMs,
        stepMs = step,
    )
    if (series.isEmpty()) return null
    val samples = series.map { (timestamp, level) ->
        EffectSample(timestampMs = timestamp, intensity = (level * 10.0).toFloat().coerceIn(0f, 10f))
    }
    return PkEffectCurve(samples, halfLife, halfLifeFromSource)
}

/** Doses arrive in µg, mg or g; the reference is always mg. */
internal fun doseAmountMg(amount: Double, unit: String): Double? {
    if (amount <= 0.0) return null
    return when (unit.trim().lowercase()) {
        "ug", "µg", "μg", "mcg" -> amount / 1000.0
        "mg", "milligram", "milligrams" -> amount
        "g", "gram", "grams" -> amount * 1000.0
        else -> null
    }
}

/** Midpoint of the moderate band in mg, or the largest dose when the band is absent. */
internal fun referenceDoseMg(substance: Substance, largestDoseMg: Double): Double? {
    substance.dosageBands["common"]?.let { band -> bandMidpointMg(band)?.let { return it } }
    substance.dosageBands["moderate"]?.let { band -> bandMidpointMg(band)?.let { return it } }
    return largestDoseMg.takeIf { it > 0.0 }
}

/** Parses "75-250 mg" or "10 µg" into a midpoint in mg. */
internal fun bandMidpointMg(text: String): Double? {
    val match = Regex("""(\d+(?:\.\d+)?)\s*(?:-\s*(\d+(?:\.\d+)?))?\s*([a-zA-Zµμ]+)""").find(text)
        ?: return null
    val low = match.groupValues[1].toDoubleOrNull() ?: return null
    val high = match.groupValues[2].toDoubleOrNull() ?: low
    val unitMg = doseAmountMg(1.0, match.groupValues[3]) ?: return null
    val midpoint = (low + high) / 2.0
    return (midpoint * unitMg).takeIf { it > 0.0 }
}

/** End of the effect window in minutes: the total stage when present, else the phase sum. */
internal fun totalDurationMinutes(phases: List<app.journal.util.DurationPhase>): Double? {
    if (phases.isEmpty()) return null
    phases.firstOrNull { it.label == "Total" }?.let { total ->
        val minutes = (total.minMinutes + total.maxMinutes) / 2.0
        if (minutes > 0.0) return minutes
    }
    var cursor = 0.0
    var sawAny = false
    for (phase in phases) {
        if (phase.label == "Afterglow" || phase.label == "Total") continue
        val minutes = (phase.minMinutes + phase.maxMinutes) / 2.0
        if (minutes > 0.0) sawAny = true
        cursor += minutes
    }
    return if (sawAny) cursor else null
}

/** Half-life caption for the card, e.g. "2.5 h" or "1 h 10 min". */
internal fun formatHalfLife(minutes: Double): String {
    val rounded = minutes.roundToInt()
    return when {
        rounded < 60 -> "$rounded min"
        rounded % 60 == 0 -> "${rounded / 60} h"
        else -> "${rounded / 60} h ${rounded % 60} min"
    }
}
