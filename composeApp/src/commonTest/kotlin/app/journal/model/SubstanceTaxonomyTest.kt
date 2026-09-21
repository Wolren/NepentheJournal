package app.journal.model

import kotlin.test.*

class SubstanceTaxonomyTest {

    private fun assertBroad(raw: String, vararg expectedIds: String) {
        assertEquals(
            expectedIds.toSet(), SubstanceTaxonomy.broadsOf(raw),
            "$raw mapped to ${SubstanceTaxonomy.broadsOf(raw)}, expected ${expectedIds.toSet()}",
        )
    }

    @Test
    fun effectLabelsMapToTheirBroad() {
        assertBroad("psychedelic", "psychedelics")
        assertBroad("hallucinogen", "psychedelics")
        assertBroad("stimulant", "stimulants")
        assertBroad("stimulant (mild)", "stimulants")
        assertBroad("depressant", "depressants")
        assertBroad("depressant (high doses)", "depressants")
        assertBroad("sedative", "depressants")
        assertBroad("anxiolytic", "depressants")
        assertBroad("opioid", "opioids")
        assertBroad("dissociative", "dissociatives")
        assertBroad("deliriant", "deliriants")
        assertBroad("entactogen", "entactogens")
        assertBroad("empathogen", "entactogens")
        assertBroad("nootropic", "nootropics")
        assertBroad("cannabinoid", "cannabinoids")
        assertBroad("cannabinoid (synthetic)", "cannabinoids")
        assertBroad("antidepressant", "medicines")
        assertBroad("antipsychotic", "medicines")
        assertBroad("supplement", "natural")
        assertBroad("oneirogen", "natural")
    }

    @Test
    fun chemicalFamiliesMapToTheirBroad() {
        assertBroad("tryptamine", "psychedelics")
        assertBroad("lysergamide", "psychedelics")
        assertBroad("phenethylamine (substituted)", "psychedelics")
        assertBroad("2c x", "psychedelics")
        assertBroad("nbome", "psychedelics")
        assertBroad("cathinone (substituted)", "stimulants")
        assertBroad("amphetamine", "stimulants")
        assertBroad("pyrrolidinophenone", "stimulants")
        assertBroad("phenidate", "stimulants")
        assertBroad("benzodiazepine", "depressants")
        assertBroad("1,4 benzodiazepine", "depressants")
        assertBroad("thienotriazolodiazepine", "depressants")
        assertBroad("barbiturate", "depressants")
        assertBroad("gabaergic", "depressants")
        assertBroad("gaba analogue (prodrug)", "depressants")
        assertBroad("morphinan", "opioids")
        assertBroad("phenylpiperidine", "opioids")
        assertBroad("arylcyclohexylamine", "dissociatives")
        assertBroad("racetam", "nootropics")
        assertBroad("eugeroic", "nootropics")
        assertBroad("thiobarbituric acid derivative", "depressants")
        assertBroad("piperazinoazepine", "medicines")
        // Diazepines are depressants even though they contain "azepine".
        assertBroad("benzodiazepine", "depressants")
        assertBroad("benzazepine", "medicines")
    }

    @Test
    fun ambiguousLabelsMapToBothBroads() {
        assertBroad("amphetamine (psychedelic)", "psychedelics", "stimulants")
        assertBroad("mdxx", "psychedelics", "entactogens")
    }

    @Test
    fun ketoAndOpioidScaffoldsDoNotLeak() {
        // Beta-keto phenethylamine is a cathinone, not a psychedelic.
        assertBroad("β keto phenethylamine", "stimulants")
        // Fentanyl scaffolds are opioids despite the piperidine/piperazine core.
        assertBroad("anilidopiperidine (opioid)", "opioids")
        assertBroad("anilinopiperidine (opioid)", "opioids")
        // Synthetic-cannabinoid indoles stay out of psychedelics.
        assertBroad("naphthoylindole (synthetic cannabinoid)", "cannabinoids")
        assertBroad("benzoylindole", "cannabinoids")
        assertBroad("indolecarboxamide", "cannabinoids")
        // Plain indoles are psychedelic scaffolds.
        assertBroad("indole", "psychedelics")
    }

    @Test
    fun unmatchedLabelsFallBackToOther() {
        assertBroad("not psychoactive", "other")
        assertBroad("ammonium salt", "other")
        assertBroad("", "other")
        assertBroad("   ", "other")
    }

    @Test
    fun nullAndEmptyInputsFallBackToOther() {
        assertEquals(setOf("other"), SubstanceTaxonomy.broadsOf(null))
        assertEquals(setOf("other"), SubstanceTaxonomy.broadsOfClasses(emptyList()))
    }

    @Test
    fun substanceBroadsAreTheUnionOfItsClasses() {
        assertEquals(
            setOf("psychedelics", "stimulants"),
            SubstanceTaxonomy.broadsOfClasses(listOf("tryptamine", "stimulant")),
        )
    }

    @Test
    fun broadDisplayOrderIsStable() {
        val ids = SubstanceTaxonomy.broads.map { it.id }
        assertEquals(ids.distinct(), ids, "broad ids must be unique")
        assertEquals("other", ids.last(), "Other stays last")
    }

    private fun korInteraction(
        target: String? = "&kappa; receptor",
        action: String? = "Full agonist",
    ) = IupharInteraction(targetName = target, action = action)

    @Test
    fun korAgonistsAreDysdelic() {
        assertTrue(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction())))
        assertTrue(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Agonist"))))
        assertTrue(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Partial agonist"))))
        assertTrue(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Biased agonist"))))
        assertTrue(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(target = "OPRK1", action = "Agonist"))))
    }

    @Test
    fun korAntagonistsAndInverseAgonistsAreNotDysdelic() {
        assertFalse(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Antagonist"))))
        assertFalse(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Inverse agonist"))))
        assertFalse(SubstanceTaxonomy.isKorAgonist(listOf(korInteraction(action = "Binding"))))
        assertFalse(SubstanceTaxonomy.isKorAgonist(emptyList()))
    }

    @Test
    fun nonKorAgonistsAreNotDysdelic() {
        assertFalse(
            SubstanceTaxonomy.isKorAgonist(
                listOf(korInteraction(target = "5-hydroxytryptamine receptor 2A", action = "Agonist")),
            ),
        )
        assertFalse(
            SubstanceTaxonomy.isKorAgonist(
                listOf(korInteraction(target = "&mu; receptor", action = "Full agonist")),
            ),
        )
    }

    @Test
    fun dysdelicSpecificsReflectActionStrength() {
        assertEquals(
            listOf("KOR full agonist"),
            SubstanceTaxonomy.dysdelicSpecifics(listOf(korInteraction())),
        )
        assertEquals(
            listOf("KOR partial agonist"),
            SubstanceTaxonomy.dysdelicSpecifics(listOf(korInteraction(action = "Partial agonist"))),
        )
        assertTrue(
            SubstanceTaxonomy.dysdelicSpecifics(listOf(korInteraction(action = "Antagonist"))).isEmpty(),
        )
    }

    @Test
    fun broadsForAddsDysdelicOnKorAgonism() {
        val kor = listOf(korInteraction())
        assertEquals(
            setOf("psychedelics", "dysdelic"),
            SubstanceTaxonomy.broadsFor(listOf("salvinorin"), kor, "Salvinorin A"),
        )
        assertEquals(
            setOf("psychedelics", "dysdelic"),
            SubstanceTaxonomy.broadsFor(listOf("salvinorin"), emptyList(), "Salvinorin A"),
        )
        assertEquals(
            listOf("KOR full agonist", "salvinorin"),
            SubstanceTaxonomy.specificsFor("dysdelic", listOf("salvinorin"), kor, "Salvinorin A"),
        )
        assertEquals(
            listOf("salvinorin"),
            SubstanceTaxonomy.specificsFor("psychedelics", listOf("salvinorin"), kor, "Salvinorin A"),
        )
    }

    @Test
    fun salviaPlantIsDysdelicWithoutAssayRows() {
        assertEquals(
            // furanolactone is an unmapped chemistry label, hence Other.
            setOf("psychedelics", "other", "dysdelic"),
            SubstanceTaxonomy.broadsFor(
                listOf("atypical hallucinogen", "furanolactone", "hallucinogen"),
                emptyList(),
                "Salvia divinorum",
            ),
        )
        assertEquals(
            listOf("KOR agonist"),
            SubstanceTaxonomy.specificsFor(
                "dysdelic",
                listOf("atypical hallucinogen", "furanolactone", "hallucinogen"),
                emptyList(),
                "Salvia divinorum",
            ),
        )
        // Variant spelling of the salvinorin analogue, classes carry no signal.
        assertTrue(
            "dysdelic" in SubstanceTaxonomy.broadsFor(
                listOf("furanolactone"), emptyList(), "Salvinorin-B-Methoxymethyl-Ether",
            ),
        )
    }

    @Test
    fun diterpeneAloneIsNotDysdelic() {
        // Grayanotoxin is a diterpene but not a KOR agonist.
        val broads = SubstanceTaxonomy.broadsFor(
            listOf("depressant", "diterpene"), emptyList(), "Grayanotoxin",
        )
        assertFalse("dysdelic" in broads, "Grayanotoxin must not be Dysdelic, got $broads")
    }

    @Test
    fun curatedCategoriesAddBroads() {
        val curated = listOf(CuratedSection("stimulant", "cathinone", "Cathinone"))
        assertEquals(
            setOf("other", "stimulants"),
            SubstanceTaxonomy.broadsFor(listOf("not psychoactive"), emptyList(), "X", curated),
        )
        // Unknown categories are skipped, never forced into Other.
        val mystery = listOf(CuratedSection("mystery", "x", "X"))
        assertEquals(
            setOf("other"),
            SubstanceTaxonomy.broadsFor(listOf("not psychoactive"), emptyList(), "X", mystery),
        )
    }

    @Test
    fun curatedLabelsComeFirstInSpecifics() {
        val curated = listOf(CuratedSection("stimulant", "cathinone", "Cathinone"))
        assertEquals(
            listOf("Cathinone", "cathinone (substituted)"),
            SubstanceTaxonomy.specificsFor(
                "stimulants", listOf("cathinone (substituted)"), emptyList(), "X", curated,
            ),
        )
    }
}
