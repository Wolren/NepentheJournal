package app.journal.ingest

import kotlin.test.*

class SubstanceClassNormalizerTest {

    @Test
    fun `empty input returns empty`() {
        assertTrue(SubstanceClassNormalizer.normalize(emptyList()).isEmpty())
    }

    @Test
    fun `singular class is preserved`() {
        assertEquals(listOf("psychedelic"), SubstanceClassNormalizer.normalize(listOf("psychedelic")))
    }

    @Test
    fun `plural maps to canonical`() {
        assertEquals(listOf("psychedelic"), SubstanceClassNormalizer.normalize(listOf("psychedelics")))
    }

    @Test
    fun `case is normalized`() {
        assertEquals(listOf("psychedelic"), SubstanceClassNormalizer.normalize(listOf("Psychedelic")))
        assertEquals(listOf("psychedelic"), SubstanceClassNormalizer.normalize(listOf("PSYCHEDELIC")))
    }

    @Test
    fun `empathogen maps to entactogen`() {
        assertEquals(listOf("entactogen"), SubstanceClassNormalizer.normalize(listOf("empathogen")))
    }

    @Test
    fun `noise labels are removed`() {
        assertEquals(emptyList<String>(), SubstanceClassNormalizer.normalize(listOf("common")))
        assertEquals(emptyList<String>(), SubstanceClassNormalizer.normalize(listOf("tentative")))
    }

    @Test
    fun `results are deduplicated and sorted`() {
        assertEquals(
            listOf("benzodiazepine", "depressant", "psychedelic"),
            SubstanceClassNormalizer.normalize(listOf("psychedelic", "depressant", "psychedelic", "benzodiazepine"))
        )
    }

    @Test
    fun `comma-joined strings are split`() {
        assertEquals(
            listOf("depressant", "psychedelic"),
            SubstanceClassNormalizer.normalize(listOf("psychedelic, depressant"))
        )
    }

    @Test
    fun `unknown class is passed through`() {
        assertEquals(listOf("mythical"), SubstanceClassNormalizer.normalize(listOf("mythical")))
    }

    @Test
    fun `stimulant variants`() {
        assertEquals(listOf("stimulant"), SubstanceClassNormalizer.normalize(listOf("stimulant")))
        assertEquals(listOf("stimulant"), SubstanceClassNormalizer.normalize(listOf("stimulants")))
    }

    @Test
    fun `depressant variants`() {
        assertEquals(listOf("depressant"), SubstanceClassNormalizer.normalize(listOf("depressant")))
        assertEquals(listOf("depressant"), SubstanceClassNormalizer.normalize(listOf("depressants")))
    }

    @Test
    fun `separator replacement`() {
        assertEquals(listOf("classical psychedelic"), SubstanceClassNormalizer.normalize(listOf("classical_psychedelic")))
        assertEquals(listOf("classical psychedelic"), SubstanceClassNormalizer.normalize(listOf("classical-psychedelic")))
    }

    @Test
    fun `blank labels are filtered`() {
        assertEquals(listOf("psychedelic"), SubstanceClassNormalizer.normalize(listOf("psychedelic", "", "  ")))
    }

    @Test
    fun `mixture of known and unknown`() {
        assertEquals(
            listOf("entactogen", "new class", "psychedelic"),
            SubstanceClassNormalizer.normalize(listOf("empathogen", "psychedelic", "new_class"))
        )
    }
}
