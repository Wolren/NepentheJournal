package app.journal.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * One-compartment PK curve with first-order absorption, the shape Piru draws:
 * C(t) = ka/(ka - ke) * (exp(-ke t) - exp(-ka t)), normalised so one unit of
 * dose peaks at 1. Doses superpose linearly, so a redose adds a second rise.
 *
 * ke = ln2 / half-life. ka is solved from the time to peak, because the peak
 * stage of the duration profile is data we have and ka is not.
 */
object PkCurve {

    /** Fastest allowed first-order absorption rate relative to ke. */
    private const val MAX_KA_RATIO = 400.0

    /**
     * Absorption rate constant that puts the peak at [tmaxMinutes].
     *
     * A peak can never sit beyond 1/ke: as ka approaches ke the peak time
     * approaches 1/ke from below, and as ka grows the peak moves toward 0.
     * A Tmax at or past that ceiling contradicts the half-life, so the peak
     * time is clamped to 0.9/ke and the half-life wins.
     */
    fun absorptionRate(ke: Double, tmaxMinutes: Double): Double {
        require(ke > 0.0) { "ke must be positive, got $ke" }
        val ceiling = 0.9 / ke
        val target = tmaxMinutes.coerceIn(ke * 1e-4, ceiling)
        var low = ke * 1.0001
        var high = ke * MAX_KA_RATIO
        repeat(80) {
            val mid = (low + high) / 2.0
            if (peakTime(ke, mid) > target) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    /** Time of the maximum of the unit-dose curve, in minutes. */
    fun peakTime(ke: Double, ka: Double): Double {
        if (abs(ka - ke) < ke * 1e-9) return 1.0 / ke
        return ln(ka / ke) / (ka - ke)
    }

    /** Unit-dose response at [minutes] after the dose. Returns 0 at t <= 0. */
    fun unitResponse(minutes: Double, ke: Double, ka: Double): Double {
        if (minutes <= 0.0) return 0.0
        if (abs(ka - ke) < ke * 1e-6) return keepPeakAtOne(minutes, ke, ka)
        val raw = ka / (ka - ke) * (exp(-ke * minutes) - exp(-ka * minutes))
        return raw / peakValue(ke, ka)
    }

    /** Peak of the unnormalised unit-dose curve. */
    fun peakValue(ke: Double, ka: Double): Double {
        if (abs(ka - ke) < ke * 1e-6) return 1.0
        val t = peakTime(ke, ka)
        return ka / (ka - ke) * (exp(-ke * t) - exp(-ka * t))
    }

    private fun keepPeakAtOne(minutes: Double, ke: Double, ka: Double): Double {
        val t = 1.0 / ke
        val peak = t * exp(-ke * t)
        return minutes * exp(-ke * minutes) / peak
    }

    /**
     * Sum of unit responses for every dose, in units of the reference dose.
     * Each entry is (timestampMs, amount in units of the reference dose).
     */
    fun series(
        doses: List<Pair<Long, Double>>,
        ke: Double,
        ka: Double,
        startMs: Long,
        endMs: Long,
        stepMs: Long,
    ): List<Pair<Long, Double>> {
        if (doses.isEmpty() || endMs <= startMs) return emptyList()
        val step = stepMs.coerceAtLeast(60_000L)
        val out = ArrayList<Pair<Long, Double>>()
        var t = startMs
        while (t <= endMs) {
            var level = 0.0
            for ((timestamp, amount) in doses) {
                if (timestamp > t) continue
                level += amount * unitResponse((t - timestamp) / 60_000.0, ke, ka)
            }
            out.add(t to level)
            if (t == endMs) break
            t = minOf(t + step, endMs)
        }
        return out
    }

    /** Minutes from a half-life in minutes. */
    fun eliminationRate(halfLifeMinutes: Double): Double {
        require(halfLifeMinutes > 0.0) { "half-life must be positive, got $halfLifeMinutes" }
        return ln(2.0) / halfLifeMinutes
    }

    /**
     * Half-life for a substance whose source carries none, inferred from the
     * effect window: a first-order decay loses about 4.3 half-lives between
     * peak and the end of the total duration.
     */
    fun estimateHalfLifeMinutes(totalDurationMinutes: Double, tmaxMinutes: Double): Double {
        val available = (totalDurationMinutes - tmaxMinutes).coerceAtLeast(1.0)
        return (available / 4.32).coerceAtLeast(10.0)
    }
}
