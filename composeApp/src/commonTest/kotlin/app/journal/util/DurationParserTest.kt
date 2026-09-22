package app.journal.util

import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiDurationRoute
import app.journal.model.DoseWikiStage
import app.journal.model.DoseWikiStages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary coverage for the duration parsing helpers extracted from the
 * substance detail DurationSection composable: empty and garbage input,
 * unit conversion, and range handling.
 */
class DurationParserTest {

    // ==================== parseDurationValue ====================

    @Test
    fun parseDurationValueEmptyOrNullishInput() {
        assertNull(parseDurationValue(""), "empty string has no number")
        assertNull(parseDurationValue("   "), "blank string has no number")
        assertNull(parseDurationValue("abc"), "pure letters have no number")
        assertNull(parseDurationValue("hours"), "unit without a number has no range")
    }

    @Test
    fun parseDurationValueGarbageInput() {
        assertNull(parseDurationValue("--"), "dashes alone are not a range")
        assertNull(parseDurationValue("?/-"), "symbols carry no number")
        // Digits with no unit are minutes, but text without digits stays null.
        assertNull(parseDurationValue("a-b-c"), "letters only")
    }

    @Test
    fun parseDurationValueSingleValueYieldsEqualBounds() {
        assertEquals(Pair(45.0, 45.0), parseDurationValue("45 min"), "single minute value")
        assertEquals(Pair(45.0, 45.0), parseDurationValue("45m"), "tight unit spelling")
        assertEquals(Pair(0.0, 0.0), parseDurationValue("0 min"), "zero is a valid value")
        assertEquals(Pair(10.0, 10.0), parseDurationValue("10"), "no unit means minutes")
    }

    @Test
    fun parseDurationValueUnitMultipliers() {
        assertEquals(Pair(180.0, 300.0), parseDurationValue("3-5 hours"), "hours to minutes")
        assertEquals(Pair(180.0, 300.0), parseDurationValue("3 - 5 hr"), "hr abbreviation")
        assertEquals(Pair(90.0, 150.0), parseDurationValue("1.5-2.5 hour"), "fractional hours")
        assertEquals(Pair(2880.0, 4320.0), parseDurationValue("2-3 days"), "days to minutes")
        assertEquals(Pair(60.0, 60.0), parseDurationValue("1 hour"), "single hour")
        assertEquals(Pair(1440.0, 1440.0), parseDurationValue("1 day"), "single day")
    }

    @Test
    fun parseDurationValueRangeForms() {
        assertEquals(Pair(10.0, 20.0), parseDurationValue("10-20"), "unitless range stays minutes")
        assertEquals(Pair(10.0, 20.0), parseDurationValue("10-20 min"), "minute range")
        assertEquals(Pair(30.0, 30.0), parseDurationValue("30 min"), "single bound repeats")
        // En dash is the range character in the seed data.
        assertEquals(Pair(60.0, 120.0), parseDurationValue("1\u20132 hours"), "en-dash range")
        assertEquals(Pair(2880.0, 2880.0), parseDurationValue("  2  days  "), "surrounding whitespace trimmed")
    }

    @Test
    fun parseDurationValueUnknownUnitIsMinutes() {
        assertEquals(Pair(1.0, 2.0), parseDurationValue("1-2 weeks"), "unlisted unit falls back to minutes")
        assertEquals(Pair(3.0, 3.0), parseDurationValue("3 seconds"), "seconds not listed, treated as minutes")
    }

    // ==================== parseDurationProfile ====================

    @Test
    fun parseDurationProfileEmptyAndUnknownKeys() {
        assertTrue(parseDurationProfile(emptyMap()).isEmpty(), "empty profile has no phases")
        assertTrue(
            parseDurationProfile(mapOf("bogus" to "5 min")).isEmpty(),
            "unknown keys are skipped"
        )
        assertTrue(
            parseDurationProfile(mapOf("total" to "6 hours")).isEmpty(),
            "total is not a phase"
        )
        assertTrue(
            parseDurationProfile(mapOf("onset" to "n/a")).isEmpty(),
            "unparseable value drops the phase"
        )
    }

    @Test
    fun parseDurationProfileOrderedPhases() {
        val phases = parseDurationProfile(
            mapOf(
                "afterglow" to "2 hours",
                "peak" to "2-3 hours",
                "onset" to "30-60 min",
            )
        )
        assertEquals(listOf("Onset", "Peak", "Afterglow"), phases.map { it.label },
            "phases come out in canonical order, not map order")
        assertEquals(30.0, phases[0].minMinutes, "onset min")
        assertEquals(60.0, phases[0].maxMinutes, "onset max")
        assertEquals("30-60 min", phases[0].display, "display keeps the raw text")
        assertEquals(120.0, phases[2].minMinutes, "afterglow hours converted")
        assertEquals(120.0, phases[2].maxMinutes, "single number yields equal bounds, even in hours")
    }

    // ==================== stageToMinutes ====================

    @Test
    fun stageToMinutesBoundaries() {
        assertNull(stageToMinutes(null), "null stage has no data")
        assertNull(stageToMinutes(DoseWikiStage(min = null, max = 5.0, unit = "min")),
            "stage without a min has no data")
        assertEquals(Pair(45.0, 45.0), stageToMinutes(DoseWikiStage(min = 45.0, max = null, unit = "min")),
            "missing max repeats min")
        assertEquals(Pair(60.0, 120.0), stageToMinutes(DoseWikiStage(min = 1.0, max = 2.0, unit = "hours")),
            "hours converted")
        assertEquals(Pair(60.0, 120.0), stageToMinutes(DoseWikiStage(min = 1.0, max = 2.0, unit = "Hr")),
            "unit match is case-insensitive")
        assertEquals(Pair(1440.0, 1440.0), stageToMinutes(DoseWikiStage(min = 1.0, max = null, unit = "days")),
            "days converted")
        assertEquals(Pair(10.0, 10.0), stageToMinutes(DoseWikiStage(min = 10.0, max = null, unit = "weeks")),
            "unknown unit stays minutes")
        assertEquals(Pair(10.0, 10.0), stageToMinutes(DoseWikiStage(min = 10.0, max = null, unit = null)),
            "missing unit stays minutes")
    }

    // ==================== formatStage ====================

    @Test
    fun formatStageRendering() {
        assertEquals("", formatStage(null), "null stage renders empty")
        assertEquals("", formatStage(DoseWikiStage(min = null, max = 2.0, unit = "min")),
            "stage without min renders empty")
        assertEquals("45.0 min", formatStage(DoseWikiStage(min = 45.0, max = null, unit = "min")),
            "missing max renders the single value")
        assertEquals("45.0 min", formatStage(DoseWikiStage(min = 45.0, max = null, unit = null)),
            "missing unit defaults to min")
        assertEquals("1.0 - 3.0 min", formatStage(DoseWikiStage(min = 1.0, max = 3.0, unit = null)),
            "missing unit on a range defaults to min")
        assertEquals("2.0 hours", formatStage(DoseWikiStage(min = 2.0, max = 2.0, unit = "hours")),
            "equal bounds render once")
        assertEquals("1.0 - 3.0 hours", formatStage(DoseWikiStage(min = 1.0, max = 3.0, unit = "hours")),
            "range bounds render as a dash range")
    }

    // ==================== parseDoseWikiDuration / getDoseWikiTotal ====================

    private fun stages(
        onset: DoseWikiStage? = null,
        peak: DoseWikiStage? = null,
        afterEffects: DoseWikiStage? = null,
        total: DoseWikiStage? = null,
    ) = DoseWikiStages(
        onset = onset, peak = peak, after_effects = afterEffects, total_duration = total
    )

    @Test
    fun parseDoseWikiDurationRoutesAndOrdering() {
        assertTrue(
            parseDoseWikiDuration(DoseWikiDuration(routes = null)).isEmpty(),
            "no routes means no phases"
        )
        assertTrue(
            parseDoseWikiDuration(DoseWikiDuration(routes = listOf(DoseWikiDurationRoute(route = "oral")))).isEmpty(),
            "route without stages means no phases"
        )
        assertTrue(
            parseDoseWikiDuration(DoseWikiDuration(routes = listOf(
                DoseWikiDurationRoute(route = "oral", stages = stages())
            ))).isEmpty(),
            "stages without values mean no phases"
        )

        val duration = DoseWikiDuration(routes = listOf(
            DoseWikiDurationRoute(
                route = "oral",
                stages = stages(
                    onset = DoseWikiStage(min = 30.0, max = 60.0, unit = "min"),
                    peak = DoseWikiStage(min = 2.0, max = 3.0, unit = "hours"),
                    afterEffects = DoseWikiStage(min = 1.0, max = 2.0, unit = "hours"),
                )
            ),
            DoseWikiDurationRoute(
                route = "smoked",
                stages = stages(onset = DoseWikiStage(min = 1.0, max = 1.0, unit = "min"))
            ),
        ))
        val phases = parseDoseWikiDuration(duration, 0)
        assertEquals(listOf("Onset", "Peak", "Afterglow"), phases.map { it.label },
            "only populated stages become phases, in stage order")
        assertEquals(120.0, phases[1].minMinutes, "peak hours converted")
        assertEquals("2.0 - 3.0 hours", phases[1].display, "peak display keeps raw text")

        val smoked = parseDoseWikiDuration(duration, 1)
        assertEquals(listOf("Onset"), smoked.map { it.label }, "second route parsed independently")
        assertEquals(Pair(1.0, 1.0), Pair(smoked[0].minMinutes, smoked[0].maxMinutes),
            "minutes left alone")

        assertTrue(parseDoseWikiDuration(duration, routeIndex = 99).isEmpty(),
            "out-of-range route index yields nothing")
    }

    @Test
    fun getDoseWikiTotalBoundaries() {
        assertNull(getDoseWikiTotal(DoseWikiDuration(routes = null)), "no routes has no total")
        assertNull(
            getDoseWikiTotal(DoseWikiDuration(routes = listOf(DoseWikiDurationRoute(route = "oral")))),
            "route without stages has no total"
        )
        assertNull(
            getDoseWikiTotal(DoseWikiDuration(routes = listOf(
                DoseWikiDurationRoute(route = "oral", stages = stages())
            ))),
            "stages without a total_duration have no total"
        )
        assertNull(
            getDoseWikiTotal(DoseWikiDuration(routes = listOf(
                DoseWikiDurationRoute(route = "oral", stages = stages(
                    total = DoseWikiStage(min = null, max = 4.0, unit = "hours")
                ))
            ))),
            "total without a min has no parseable range"
        )

        val duration = DoseWikiDuration(routes = listOf(
            DoseWikiDurationRoute(route = "oral", stages = stages(
                total = DoseWikiStage(min = 4.0, max = 6.0, unit = "hours")
            ))
        ))
        val total = getDoseWikiTotal(duration)
        assertEquals(240.0, total?.first, "total min hours converted")
        assertEquals(360.0, total?.second, "total max hours converted")
        assertEquals("4.0 - 6.0 hours", total?.third, "total display text")
    }
}
