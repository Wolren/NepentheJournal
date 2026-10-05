package app.journal.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Boundary coverage for the dose band / dot meter helpers behind the session
 * card's dose rows: free-text band parsing, unit normalisation (µg vs ug vs
 * mcg), reference-dose selection, and display formatting.
 */
class DoseFormatTest {

    // ==================== parseDoseBand ====================

    @Test
    fun parseDoseBandRangesAndSingles() {
        assertEquals(ParsedDoseBand(75.0, 150.0, "µg"), parseDoseBand("75-150 µg"), "classic range")
        assertEquals(ParsedDoseBand(0.5, 1.0, "mL"), parseDoseBand("0.5-1 mL"), "fractional bounds")
        assertEquals(ParsedDoseBand(300.0, 300.0, "mg"), parseDoseBand("300 mg"), "single value repeats as max")
        assertEquals(ParsedDoseBand(60.0, 120.0, "mg"), parseDoseBand("60\u2013120 mg"), "en dash ranges parse")
        assertEquals(ParsedDoseBand(75.0, 150.0, "µg"), parseDoseBand("75-150µg"), "no space before unit")
        assertEquals(ParsedDoseBand(150.0, 150.0, ""), parseDoseBand("150"), "unitless band keeps an empty unit")
    }

    @Test
    fun parseDoseBandRejectsText() {
        assertNull(parseDoseBand(""), "empty has no number")
        assertNull(parseDoseBand("   "), "blank has no number")
        assertNull(parseDoseBand("n/a"), "text has no number")
        assertNull(parseDoseBand("as needed"), "free text has no number")
    }

    // ==================== normalizeDoseUnit ====================

    @Test
    fun normalizeDoseUnitCanonicalSpellings() {
        assertEquals("ug", normalizeDoseUnit("µg"), "micro sign")
        assertEquals("ug", normalizeDoseUnit("μg"), "greek mu")
        assertEquals("ug", normalizeDoseUnit("mcg"), "abbreviation used in seeds")
        assertEquals("ug", normalizeDoseUnit(" UG "), "case and padding are dropped")
        assertEquals("mg", normalizeDoseUnit("mg"), "already canonical")
        assertEquals("ml", normalizeDoseUnit("mL"), "case folded so routes compare equal")
        assertEquals("", normalizeDoseUnit("  "), "blank stays blank")
    }

    // ==================== referenceDoseAmount ====================

    private val lsdBands = mapOf(
        "threshold" to "15-25 µg",
        "light" to "50-75 µg",
        "common" to "75-150 µg",
        "strong" to "150-300 µg",
        "heavy" to "300-600 µg",
    )

    @Test
    fun referenceDoseAmountPrefersTypicalBands() {
        assertEquals(150.0, referenceDoseAmount(lsdBands, "µg"), "common band top is the reference")
        assertEquals(150.0, referenceDoseAmount(lsdBands, "ug"), "unit normalisation makes spellings equal")
        assertEquals(300.0, referenceDoseAmount(lsdBands - "common", "µg"), "falls through to strong")
        assertEquals(75.0, referenceDoseAmount(lsdBands - "common" - "strong", "µg"), "then light")
        assertEquals(25.0, referenceDoseAmount(lsdBands - "common" - "strong" - "light", "µg"), "then threshold")
    }

    @Test
    fun referenceDoseAmountRejectsUnitMismatchAndUnknownBands() {
        assertNull(referenceDoseAmount(lsdBands, "mg"), "no band in mg means no scale")
        assertNull(referenceDoseAmount(lsdBands, ""), "a unitless dose has no reference")
        assertNull(referenceDoseAmount(emptyMap(), "µg"), "no bands at all")
        assertNull(
            referenceDoseAmount(mapOf("heavy" to "300-600 µg"), "µg"),
            "heavy is a toxicity band, never a reference scale"
        )
        assertNull(
            referenceDoseAmount(mapOf("common" to "as needed"), "µg"),
            "unparseable band is skipped"
        )
        assertEquals(
            150.0,
            referenceDoseAmount(mapOf("common" to "0-150 µg"), "µg"),
            "the reference is the top of the band, whatever its bottom is"
        )
        assertNull(
            referenceDoseAmount(mapOf("common" to "0-0 µg"), "µg"),
            "a band that tops out at zero gives no scale at all"
        )
    }

    // ==================== formatDoseAmount ====================

    @Test
    fun formatDoseAmountDropsFloatNoise() {
        assertEquals("2400", formatDoseAmount(2400.0), "whole numbers keep no decimals")
        assertEquals("150", formatDoseAmount(150.0), "small whole numbers")
        assertEquals("2.5", formatDoseAmount(2.5), "true halves survive")
        assertEquals("0.5", formatDoseAmount(0.5), "sub-unit values")
        assertEquals("1234.5", formatDoseAmount(1234.5), "one decimal kept")
        assertEquals("0.67", formatDoseAmount(0.666), "at most two decimals")
        assertEquals("", formatDoseAmount(Double.NaN), "NaN renders nothing")
        assertEquals("", formatDoseAmount(Double.POSITIVE_INFINITY), "infinity renders nothing")
    }
}
