package app.journal.ingest

import app.journal.model.DoseWikiStage
import app.journal.model.DoseWikiStages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Free-text kinetics from the seed must become usable minutes, and never from animals. */
class PharmacokineticsTest {

    @Test
    fun halfLifeRangesBecomeAMidpointInMinutes() {
        assertEquals(120.0, Pharmacokinetics.halfLifeMinutes("1-3 hours")!!, 0.001)
        assertEquals(39.0, Pharmacokinetics.halfLifeMinutes("39 min")!!, 0.001)
        assertEquals(630.0, Pharmacokinetics.halfLifeMinutes("10 to 11 h")!!, 0.001)
        assertEquals(1440.0, Pharmacokinetics.halfLifeMinutes("1 day")!!, 0.001)
    }

    @Test
    fun animalAndInVitroValuesAreRejected() {
        assertNull(Pharmacokinetics.halfLifeMinutes("half-life in rats is approximately 91 min"))
        assertNull(Pharmacokinetics.halfLifeMinutes("measured in vitro, 4 h"))
        assertNull(Pharmacokinetics.halfLifeMinutes("no value"))
        assertNull(Pharmacokinetics.halfLifeMinutes(null))
    }

    @Test
    fun tmaxComesFromThePeakStage() {
        val stages = DoseWikiStages(peak = DoseWikiStage(min = 3.0, max = 5.0, unit = "hours"))
        assertEquals(240.0, Pharmacokinetics.tmaxMinutes(stages)!!, 0.001)
    }

    @Test
    fun tmaxFallsBackToOnsetPlusComeup() {
        val stages = DoseWikiStages(
            onset = DoseWikiStage(min = 20.0, max = 40.0, unit = "minutes"),
            come_up = DoseWikiStage(min = 45.0, max = 90.0, unit = "minutes"),
        )
        assertEquals(30.0 + 67.5, Pharmacokinetics.tmaxMinutes(stages)!!, 0.001)
    }

    @Test
    fun noStagesMeansNoTimeToPeak() {
        assertNull(Pharmacokinetics.tmaxMinutes(null))
        assertNull(Pharmacokinetics.tmaxMinutes(DoseWikiStages()))
    }
}
