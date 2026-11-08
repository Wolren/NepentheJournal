package app.journal.ui.session

import app.journal.model.Dose
import app.journal.model.Substance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dose scaling, unit handling and the fallback path of the derived PK curve. */
class PkEffectCurveTest {

    private fun substance(
        halfLife: Double? = null,
        tmax: Double? = null,
        bands: Map<String, String> = emptyMap(),
        profile: Map<String, String> = emptyMap(),
    ) = Substance(
        id = "test:1",
        name = "Test",
        routesOfAdministration = listOf("oral"),
        dosageBands = bands,
        durationProfile = profile,
        halfLifeMinutes = halfLife,
        tmaxMinutes = tmax,
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "system",
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private fun dose(amount: Double, unit: String, atMinutes: Long) = Dose(
        id = "d:$atMinutes",
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "test",
        sessionId = "s:1",
        substanceId = "test:1",
        routeOfAdministration = "oral",
        amount = amount,
        unit = unit,
        timestamp = atMinutes * 60_000L,
    )

    @Test
    fun aModerateDosePeaksAtTheTopOfTheScale() {
        val curve = pkEffectSamples(
            doses = listOf(dose(100.0, "mg", 0)),
            substance = substance(halfLife = 120.0, tmax = 45.0, bands = mapOf("common" to "50-150 mg")),
            startMs = 0L,
            endMs = 8 * 3_600_000L,
        )
        assertNotNull(curve)
        assertEquals(10f, curve.samples.maxOf { it.intensity }, 0.1f)
        assertTrue(curve.halfLifeFromSource)
    }

    @Test
    fun aLightDoseDrawsLowerThanAModerateOne() {
        val light = pkEffectSamples(
            listOf(dose(25.0, "mg", 0)),
            substance(halfLife = 120.0, tmax = 45.0, bands = mapOf("common" to "50-150 mg")),
            0L, 8 * 3_600_000L,
        )
        val moderate = pkEffectSamples(
            listOf(dose(100.0, "mg", 0)),
            substance(halfLife = 120.0, tmax = 45.0, bands = mapOf("common" to "50-150 mg")),
            0L, 8 * 3_600_000L,
        )
        assertNotNull(light)
        assertNotNull(moderate)
        assertTrue(
            light.samples.maxOf { it.intensity } < moderate.samples.maxOf { it.intensity },
            "25 mg must peak below 100 mg"
        )
    }

    @Test
    fun microgramsScaleAgainstTheBandUnit() {
        val curve = pkEffectSamples(
            listOf(dose(100.0, "µg", 0)),
            substance(halfLife = 180.0, tmax = 90.0, bands = mapOf("common" to "75-150 µg")),
            0L, 12 * 3_600_000L,
        )
        assertNotNull(curve)
        // The grid samples, so the peak can land just short of 10.
        assertTrue(curve.samples.maxOf { it.intensity } in 8.5f..10f)
    }

    @Test
    fun withoutAHalfLifeTheDurationProfileSuppliesAnEstimate() {
        val curve = pkEffectSamples(
            listOf(dose(100.0, "mg", 0)),
            substance(
                halfLife = null,
                tmax = null,
                bands = mapOf("common" to "50-150 mg"),
                profile = mapOf("onset" to "20-40 min", "peak" to "1-2 h", "total" to "4-6 h"),
            ),
            0L, 8 * 3_600_000L,
        )
        assertNotNull(curve)
        assertTrue(!curve.halfLifeFromSource, "this value must be flagged as an estimate")
        assertTrue(curve.halfLifeMinutes > 10.0)
    }

    @Test
    fun aSubstanceWithNoTimingAtAllYieldsNoCurve() {
        val curve = pkEffectSamples(
            listOf(dose(100.0, "mg", 0)),
            substance(halfLife = null, tmax = null),
            0L, 8 * 3_600_000L,
        )
        assertNull(curve, "no half-life and no duration means no invented curve")
    }

    @Test
    fun bandMidpointHandlesMilligramsMicrogramsAndGrams() {
        assertEquals(162.5, bandMidpointMg("75-250 mg")!!, 0.001)
        assertEquals(0.01, bandMidpointMg("10 µg")!!, 0.0001)
        assertEquals(2000.0, bandMidpointMg("1-3 g")!!, 0.001)
        assertNull(bandMidpointMg("plenty"))
    }

    @Test
    fun aRedoseAddsASecondRise() {
        val s = substance(halfLife = 90.0, tmax = 30.0, bands = mapOf("common" to "50-150 mg"))
        val single = pkEffectSamples(listOf(dose(100.0, "mg", 0)), s, 0L, 10 * 3_600_000L)
        val double = pkEffectSamples(
            listOf(dose(100.0, "mg", 0), dose(100.0, "mg", 120)),
            s, 0L, 10 * 3_600_000L,
        )
        assertNotNull(single)
        assertNotNull(double)
        // Compare an hour after the redose: at the instant of the dose the new amount is still zero.
        fun afterRedose(curve: PkEffectCurve) =
            curve.samples.minBy { abs(it.timestampMs - 180 * 60_000L) }.intensity
        assertTrue(afterRedose(double) > afterRedose(single))
    }
}
