package app.journal.ui.session

import app.journal.model.CheckIn
import app.journal.model.TimelineEvent
import app.journal.util.DurationPhase

/**
 * One point of a session's effect curve. Intensity uses the app-wide 0..10
 * scale so logged and derived curves are interchangeable.
 */
data class EffectSample(val timestampMs: Long, val intensity: Float)

/**
 * What the user actually logged: timeline events that carry an intensity plus
 * every check-in, in chronological order. Duplicate timestamps are fine -
 * the renderer interpolates straight through them.
 */
fun loggedEffectSamples(
    events: List<TimelineEvent>,
    checkins: List<CheckIn>,
): List<EffectSample> =
    (events.mapNotNull { event -> event.intensity?.let { EffectSample(event.timestamp, it) } } +
        checkins.map { EffectSample(it.timestamp, it.overallIntensity) })
        .sortedBy { it.timestampMs }

/**
 * Terminal intensity (0..1) of each duration phase. Onset ramps up, the peak
 * holds, the offset bleeds off into a faint afterglow - the trapezoid shape
 * PsychonautWiki's effect timeline draws, derived here from the substance's
 * own duration profile instead of a hard-coded curve.
 */
private val phaseEndLevel = mapOf(
    "Onset" to 0.30f,
    "Comeup" to 1.00f,
    "Peak" to 1.00f,
    "Offset" to 0.25f,
    "Afterglow" to 0.00f,
)

/**
 * Build an intensity curve for a session that has no check-ins yet, from the
 * substance's parsed duration profile. Phases are laid end to end using the
 * midpoint of each duration range, anchored at [anchorMs] (the first dose).
 * Returns an empty list when nothing can be derived, so callers show an
 * honest empty state rather than an invented curve.
 */
fun synthesizedEffectSamples(phases: List<DurationPhase>, anchorMs: Long): List<EffectSample> {
    if (phases.isEmpty()) return emptyList()
    val points = mutableListOf(EffectSample(anchorMs, 0f))
    var cursor = anchorMs
    var level = 0f

    fun add(timestampMs: Long, intensity: Float) {
        val last = points.lastOrNull()
        if (last != null && last.timestampMs == timestampMs) {
            if (last.intensity != intensity) points[points.lastIndex] = EffectSample(timestampMs, intensity)
            return
        }
        if (last != null && last.timestampMs > timestampMs) return
        points.add(EffectSample(timestampMs, intensity))
    }

    for (phase in phases) {
        val target = phaseEndLevel[phase.label] ?: continue
        val midMinutes = (phase.minMinutes + phase.maxMinutes) / 2.0
        val durationMs = (midMinutes * 60_000.0).toLong().coerceAtLeast(60_000L)
        add(cursor, level * 10f)
        cursor += durationMs
        level = target
        add(cursor, level * 10f)
    }
    // A profile without an afterglow ends mid-air; bleed the tail to zero so
    // the curve always comes back to the baseline.
    if (level > 0f) {
        val tail = ((cursor - anchorMs) / 4).coerceAtLeast(15 * 60_000L)
        cursor += tail
        add(cursor, 0f)
    }
    return points
}

/** Intensity of the curve at [timestampMs], linearly interpolated, clamped outside. */
fun effectIntensityAt(samples: List<EffectSample>, timestampMs: Long): Float {
    if (samples.isEmpty()) return 0f
    if (timestampMs <= samples.first().timestampMs) return samples.first().intensity
    if (timestampMs >= samples.last().timestampMs) return samples.last().intensity
    for (i in 0 until samples.size - 1) {
        val a = samples[i]
        val b = samples[i + 1]
        if (timestampMs in a.timestampMs..b.timestampMs) {
            val span = (b.timestampMs - a.timestampMs).toFloat()
            if (span <= 0f) return b.intensity
            val t = (timestampMs - a.timestampMs) / span
            return a.intensity + (b.intensity - a.intensity) * t
        }
    }
    return samples.last().intensity
}
