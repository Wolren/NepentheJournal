package app.journal.ui.substances.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class PharmacologyAffinityTest {

    @Test
    fun invalidAffinitiesReturnNotAvailable() {
        assertEquals("n/a", formatAffinity(0.0))
        assertEquals("n/a", formatAffinity(-1.0))
        assertEquals("n/a", formatAffinity(Double.NaN))
        assertEquals("n/a", formatAffinity(Double.POSITIVE_INFINITY))
    }

    @Test
    fun validAffinitiesKeepTheExistingFormat() {
        assertEquals("5.0", formatAffinity(5.0))
    }
}
