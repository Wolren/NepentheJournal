package app.journal.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The counting rules behind the session card's dose dot matrix: how many dots
 * a dose is worth against a reference amount, when the last dot is hollow, and
 * where the matrix stops growing.
 */
class DoseDotMeterTest {

    @Test
    fun noUsableReferenceMeansNoMeter() {
        assertNull(doseDots(2400.0, null), "no reference, no scale")
        assertNull(doseDots(2400.0, 0.0), "a zero reference would divide by zero")
        assertNull(doseDots(2400.0, -600.0), "a negative reference is nonsense")
    }

    @Test
    fun unusableAmountsMeanNoMeter() {
        assertNull(doseDots(0.0, 600.0), "a zero dose is not a row worth metering")
        assertNull(doseDots(-10.0, 600.0), "negative doses cannot exist, but must not crash")
        assertNull(doseDots(Double.NaN, 600.0), "NaN must not draw dots")
        assertNull(doseDots(Double.POSITIVE_INFINITY, 600.0), "infinity must not draw dots")
    }

    @Test
    fun oneReferenceIsFourFilledDots() {
        val dots = doseDots(600.0, 600.0)
        assertEquals(DoseDots(filled = 4, hasPartial = false, total = 4), dots, "four dots per reference dose")
    }

    @Test
    fun partialRemainderRendersAsAHollowDot() {
        val dots = doseDots(1150.0, 600.0)
        assertEquals(7, dots?.filled, "seven whole quarters")
        assertEquals(true, dots?.hasPartial, "the remainder is still worth showing")
        assertEquals(8, dots?.total, "filled plus one hollow")
    }

    @Test
    fun exactMultiplesHaveNoHollowDot() {
        val dots = doseDots(1050.0, 600.0)
        assertEquals(7, dots?.filled, "seven quarters exactly")
        assertEquals(false, dots?.hasPartial, "nothing left over, so no hollow dot")
        assertEquals(7, dots?.total, "just the filled ones")
    }

    @Test
    fun aFractionOfADotShowsOneHollowDot() {
        val dots = doseDots(30.0, 600.0)
        assertEquals(0, dots?.filled, "not even one quantum filled")
        assertEquals(true, dots?.hasPartial, "but the dose exists")
        assertEquals(1, dots?.total, "so it renders as a single hollow dot")
    }

    @Test
    fun dustRendersNothing() {
        assertNull(doseDots(1.0, 600.0), "a sliver of a dot would read as a full row to the eye")
    }

    @Test
    fun theMatrixNeverExceedsTwentyDots() {
        val dots = doseDots(1_000_000.0, 600.0)
        assertEquals(DOSE_DOT_MAX, dots?.total, "capped at the 4x5 grid")
        assertEquals(DOSE_DOT_MAX, dots?.filled, "the cap swallows the remainder")
        assertEquals(false, dots?.hasPartial, "a capped matrix is always fully filled")
    }
}
