package app.journal.data

import app.journal.model.*
import app.journal.model.rules.InteractionDedupe
import kotlin.test.*

class InteractionDedupeTest {

    private val now = 1_000_000L

    private fun row(
        id: String,
        a: String,
        b: String,
        risk: InteractionRisk,
        description: String? = null,
        sources: List<String> = emptyList()
    ) = Interaction(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceAId = a, substanceBId = b, riskLevel = risk,
        description = description, sources = sources
    )

    // Same display name, different id schemes: the lithium pattern.
    private val names = mapOf(
        "cid:1" to "LSD",
        "pwiki:lithium" to "Lithium",
        "dw:lithium" to "Lithium"
    )
    private val nameOf: (String) -> String = { names[it] ?: it }

    @Test
    fun sameNameDifferentIdsCollapseToOne() {
        val rows = listOf(
            row("seed:1", "cid:1", "pwiki:lithium", InteractionRisk.DANGEROUS),
            row("dw:1", "cid:1", "dw:lithium", InteractionRisk.DANGEROUS, "Seizure reports.")
        )
        val out = InteractionDedupe.dedupe(rows, nameOf)
        assertEquals(1, out.size, "info-poor duplicate must go invisible")
        assertEquals("Seizure reports.", out[0].description)
    }

    @Test
    fun highestSeverityWins() {
        val rows = listOf(
            row("a", "cid:1", "pwiki:lithium", InteractionRisk.UNSAFE, "Mild concern."),
            row("b", "cid:1", "dw:lithium", InteractionRisk.DANGEROUS)
        )
        val out = InteractionDedupe.dedupe(rows, nameOf)
        assertEquals(1, out.size)
        assertEquals(InteractionRisk.DANGEROUS, out[0].riskLevel)
        // Severity wins but the longer description still surfaces.
        assertEquals("Mild concern.", out[0].description)
    }

    @Test
    fun sourcesMerge() {
        val rows = listOf(
            row("a", "cid:1", "pwiki:lithium", InteractionRisk.DANGEROUS, sources = listOf("psychonautwiki")),
            row("b", "cid:1", "dw:lithium", InteractionRisk.DANGEROUS, "Reason.", sources = listOf("dosewiki"))
        )
        val out = InteractionDedupe.dedupe(rows, nameOf)
        assertEquals(1, out.size)
        assertTrue(out[0].sources.containsAll(listOf("psychonautwiki", "dosewiki")))
    }

    @Test
    fun distinctPairsUntouched() {
        val rows = listOf(
            row("a", "cid:1", "pwiki:lithium", InteractionRisk.DANGEROUS),
            row("b", "cid:1", "dw:lithium", InteractionRisk.DANGEROUS, "Reason."),
            row("c", "cid:1", "cid:2", InteractionRisk.UNSAFE)
        )
        val names2 = names + ("cid:2" to "MDMA")
        val out = InteractionDedupe.dedupe(rows) { names2[it] ?: it }
        assertEquals(2, out.size)
    }

    @Test
    fun richerPrefersSeverityThenInfo() {
        val poor = row("poor", "a", "b", InteractionRisk.UNSAFE, "x".repeat(50))
        val rich = row("rich", "a", "b", InteractionRisk.DANGEROUS)
        assertSame(rich, InteractionDedupe.richer(poor, rich))
        assertSame(rich, InteractionDedupe.richer(rich, poor))
        val longer = row("long", "a", "b", InteractionRisk.DANGEROUS, "y".repeat(60))
        assertSame(longer, InteractionDedupe.richer(rich, longer))
    }
}
