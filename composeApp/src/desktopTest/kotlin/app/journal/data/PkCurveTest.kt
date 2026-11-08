package app.journal.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PK contract: the peak lands on the requested time, the tail loses half its
 * value every half-life, and doses superpose linearly.
 */
class PkCurveTest {

    @Test
    fun thePeakLandsOnTheRequestedTimeToPeak() {
        val ke = PkCurve.eliminationRate(120.0)
        for (tmax in listOf(20.0, 45.0, 90.0)) {
            val ka = PkCurve.absorptionRate(ke, tmax)
            val actual = PkCurve.peakTime(ke, ka)
            assertTrue(
                abs(actual - tmax) < tmax * 0.01,
                "Tmax $tmax gave a peak at $actual with ka=$ka"
            )
        }
    }

    @Test
    fun theCurvePeaksAtOneAndDecaysByHalfEachHalfLife() {
        val halfLife = 120.0
        val ke = PkCurve.eliminationRate(halfLife)
        val ka = PkCurve.absorptionRate(ke, 45.0)
        val atPeak = PkCurve.unitResponse(PkCurve.peakTime(ke, ka), ke, ka)
        assertEquals(1.0, atPeak, 0.001)

        // After absorption, concentration decays exponentially: c(t + t½) = c(t) / 2.
        val base = 600.0
        val later = base + halfLife
        assertEquals(
            PkCurve.unitResponse(base, ke, ka) / 2.0,
            PkCurve.unitResponse(later, ke, ka),
            0.002
        )
    }

    @Test
    fun aTmaxBeyondThePeakCeilingStillProducesAUsableCurve() {
        val ke = PkCurve.eliminationRate(30.0)
        val ka = PkCurve.absorptionRate(ke, 500.0)
        val peak = PkCurve.peakTime(ke, ka)
        assertTrue(peak <= 0.9 / ke + 1e-6, "peak $peak must stay under 0.9/ke")
        assertTrue(PkCurve.unitResponse(peak, ke, ka) > 0.5)
    }

    @Test
    fun aRedoseRaisesTheLevel() {
        val ke = PkCurve.eliminationRate(90.0)
        val ka = PkCurve.absorptionRate(ke, 40.0)
        val start = 0L
        val single = PkCurve.series(listOf(0L to 1.0), ke, ka, start, 240 * 60_000L, 5 * 60_000L)
        val redosed = PkCurve.series(
            listOf(0L to 1.0, 120 * 60_000L to 1.0),
            ke, ka, start, 240 * 60_000L, 5 * 60_000L
        )
        // An hour after the redose: the second dose has entered, the first has not left.
        val levelAt = { series: List<Pair<Long, Double>> ->
            series.minBy { abs(it.first - 180 * 60_000L) }.second
        }
        assertTrue(
            levelAt(redosed) > levelAt(single),
            "the second dose must add to the first"
        )
    }

    @Test
    fun estimatedHalfLifeLeavesFourDecadesBetweenPeakAndEnd() {
        val estimated = PkCurve.estimateHalfLifeMinutes(totalDurationMinutes = 360.0, tmaxMinutes = 60.0)
        assertEquals(300.0 / 4.32, estimated, 0.01)
    }
}
