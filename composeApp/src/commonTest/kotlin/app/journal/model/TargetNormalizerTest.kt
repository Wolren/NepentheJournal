package app.journal.model

import kotlin.test.*

class TargetNormalizerTest {

    private fun keyOf(vararg names: String): Set<String> =
        names.map { TargetNormalizer.normalize(it).key }.toSet()

    private fun assertSameTarget(vararg names: String) {
        assertEquals(
            1, keyOf(*names).size,
            "expected one group for ${names.toList()}, got ${names.associateWith { TargetNormalizer.normalize(it).key }}"
        )
    }

    private fun assertDifferentTargets(first: String, second: String) {
        assertNotEquals(
            TargetNormalizer.normalize(first).key,
            TargetNormalizer.normalize(second).key,
            "$first and $second must stay separate"
        )
    }

    @Test
    fun serotoninVariantsMergeAcrossSources() {
        assertSameTarget(
            "5-HT2A",
            "5-hydroxytryptamine receptor 2A",
            "5-HT<sub>2A</sub> receptor",
            "5HT2A",
        )
        assertEquals("5-HT2A", TargetNormalizer.normalize("5-hydroxytryptamine receptor 2A").label)
    }

    @Test
    fun serotoninAssaySuffixMerges() {
        assertSameTarget("5-HT7L", "5-hydroxytryptamine receptor 7", "5-HT7")
        assertSameTarget("5-HT5a", "5-hydroxytryptamine receptor 5A")
    }

    @Test
    fun serotoninSubtypesStaySeparate() {
        assertDifferentTargets("5-HT2", "5-HT2A")
        assertDifferentTargets("5-HT2A", "5-HT2C")
        assertDifferentTargets("5-HT5A", "5-HT5B")
    }

    @Test
    fun dopamineVariantsMerge() {
        assertSameTarget("D2", "DOPAMINE D2")
        assertSameTarget("D1", "DOPAMINE D1", "D(1A) dopamine receptor")
        assertSameTarget("D3", "DOPAMINE D3", "D(3) dopamine receptor")
        assertSameTarget("D4", "DOPAMINE D4", "D(4) dopamine receptor")
        assertEquals("D1", TargetNormalizer.normalize("D(1A) dopamine receptor").label)
    }

    @Test
    fun ambiguousDopamineSubtypeStaysSeparate() {
        // D(1B) is the non-mammalian D1B subtype (D5-like), not D1.
        assertDifferentTargets("D(1B) dopamine receptor", "D1")
    }

    @Test
    fun adrenergicVariantsMerge() {
        assertSameTarget("Alpha-1A adrenergic receptor", "alpha1A", "α1A")
        assertSameTarget("adrenergic Beta2", "β2")
        assertEquals("Alpha-1A", TargetNormalizer.normalize("α1A").label)
    }

    @Test
    fun genericAdrenergicStaysSeparate() {
        assertDifferentTargets("Adrenergic Alpha", "Alpha-1A adrenergic receptor")
    }

    @Test
    fun histamineVariantsMerge() {
        assertSameTarget("HISTAMINE H1", "H1", "Histamine H1 receptor")
        assertEquals("H1", TargetNormalizer.normalize("HISTAMINE H1").label)
    }

    @Test
    fun transporterVariantsMerge() {
        assertSameTarget("SERT", "Sodium-dependent serotonin transporter")
        assertSameTarget("DAT", "Sodium-dependent dopamine transporter")
        assertSameTarget(
            "NET",
            "Norepinephrine transporter",
            "Sodium-dependent noradrenaline transporter"
        )
    }

    @Test
    fun taarAndOpioidVariantsMerge() {
        assertSameTarget("TAAR1", "trace amine-associated receptor")
        assertEquals("Mu opioid", TargetNormalizer.normalize("Mu-type opioid receptor").label)
        assertEquals("Kappa opioid", TargetNormalizer.normalize("Kappa-type opioid receptor").label)
    }

    @Test
    fun glutamateVariantsMerge() {
        assertSameTarget("Metabotropic glutamate receptor 2", "mGluR2")
    }

    @Test
    fun unknownTargetsKeepOwnGroup() {
        val a = TargetNormalizer.normalize("Carnitine O-palmitoyltransferase 2, mitochondrial")
        val b = TargetNormalizer.normalize("Vasopressin V1a receptor")
        assertNotEquals(a.key, b.key)
        assertEquals("Unknown target", TargetNormalizer.normalize(null).label)
        assertEquals("Unknown target", TargetNormalizer.normalize("  ").label)
    }
}
