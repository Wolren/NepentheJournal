package app.journal.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DoseEquivalenceTest {

    @Test
    fun diazepamEquivalentUsesTheAshtonBand() {
        val alprazolam = DoseEquivalence.diazepamEquivalent("Alprazolam", 0.5)
        assertNotNull(alprazolam)
        assertEquals(10.0, alprazolam.start, 0.001)
        assertEquals(10.0, alprazolam.endInclusive, 0.001)

        val flurazepam = DoseEquivalence.diazepamEquivalent("Flurazepam", 30.0)
        assertNotNull(flurazepam)
        assertEquals(10.0, flurazepam.start, 0.001)
        assertEquals(20.0, flurazepam.endInclusive, 0.001)
    }

    @Test
    fun convertingBetweenBenzodiazepinesKeepsTheBand() {
        val toAlprazolam = DoseEquivalence.convertBenzodiazepine("Diazepam", "Alprazolam", 10.0)
        assertNotNull(toAlprazolam)
        assertEquals(0.5, toAlprazolam.start, 0.001)
        assertEquals(0.5, toAlprazolam.endInclusive, 0.001)

        val toFlurazepam = DoseEquivalence.convertBenzodiazepine("Diazepam", "Flurazepam", 10.0)
        assertNotNull(toFlurazepam)
        assertEquals(15.0, toFlurazepam.start, 0.001)
        assertEquals(30.0, toFlurazepam.endInclusive, 0.001)
    }

    @Test
    fun opioidFactorsFollowCdc() {
        assertEquals(15.0, DoseEquivalence.morphineEquivalent("Oxycodone", 10.0)!!, 0.001)
        assertEquals(2.0, DoseEquivalence.convertOpioid("Morphine", "Codeine", 0.3)!!, 0.001)
        assertEquals(5.0, DoseEquivalence.morphineEquivalent("Hydromorphone", 1.0)!!, 0.001)
    }

    @Test
    fun refusedRowsSayWhy() {
        assertNull(DoseEquivalence.morphineEquivalent("Methadone", 10.0))
        assertEquals(true, DoseEquivalence.refusalFor("Methadone")!!.contains("variable"))
        assertEquals(true, DoseEquivalence.refusalFor("Buprenorphine")!!.contains("ceiling"))
        assertEquals(3, DoseEquivalence.refusalList.size)
    }

    @Test
    fun prodrugsNameTheirActiveSpecies() {
        assertEquals("psilocin", Prodrugs.forSubstance("Psilocybin")?.metabolite)
        assertEquals("LSD", Prodrugs.forSubstance("1P-LSD")?.metabolite)
        assertEquals("psilocin", Prodrugs.forSubstance("4-AcO-DMT")?.metabolite)
        assertNull(Prodrugs.forSubstance("LSD"))
        assertNull(Prodrugs.forSubstance("Caffeine"))
    }

    @Test
    fun metaboliteHalfLifePrefersItsOwnRow() {
        val psilocybin = Prodrugs.forSubstance("Psilocybin")!!
        assertEquals(50.0, Prodrugs.metaboliteHalfLife(psilocybin) { 50.0 })
        val lisdex = Prodrugs.forSubstance("Lisdexamfetamine")!!
        assertEquals(581.0, Prodrugs.metaboliteHalfLife(lisdex) { 0.0 })
        assertEquals(null, Prodrugs.metaboliteHalfLife(Prodrugs.forSubstance("1P-LSD")!!) { null })
    }
}
