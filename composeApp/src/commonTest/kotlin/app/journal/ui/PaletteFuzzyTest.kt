package app.journal.ui

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteFuzzyTest {

    @Test
    fun aPrefixScoresBetterThanAHitWithGaps() {
        val prefix = fuzzyScore("Sessions", "sess")
        val gappy = fuzzyScore("Press session", "sess")

        assertNotNull(prefix)
        assertNotNull(gappy)
        assertTrue(prefix < gappy, "prefix $prefix should beat gappy $gappy")
    }

    @Test
    fun everyCharacterMustAppearInOrder() {
        assertNull(fuzzyScore("Calendar", "xyz"))
        assertNotNull(fuzzyScore("Calendar", "clr"))
    }

    @Test
    fun matchingIgnoresCase() {
        assertNotNull(fuzzyScore("Dashboard", "DASH"))
    }

    @Test
    fun anEmptyQueryMatchesEverything() {
        assertEqualsZero(fuzzyScore("Anything", ""))
    }

    private fun assertEqualsZero(value: Int?) {
        assertTrue(value == 0, "expected 0, was $value")
    }
}
