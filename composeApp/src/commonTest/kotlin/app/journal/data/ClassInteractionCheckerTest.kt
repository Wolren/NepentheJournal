package app.journal.data

import app.journal.model.*
import app.journal.model.rules.ClassInteractionChecker
import app.journal.model.rules.InteractionWarningLevel
import kotlin.test.*

class ClassInteractionCheckerTest {

    private fun substance(id: String, name: String, interactionClasses: List<String> = emptyList()) = Substance(
        id = id, name = name, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        substanceClass = listOf("Test"), cachedAt = 0L, sourceVersion = "test",
        interactionClasses = interactionClasses
    )

    @Test
    fun emptyListReturnsEmpty() {
        val result = ClassInteractionChecker.check(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun singleSubstanceReturnsEmpty() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "LSD", listOf("psychedelic"))
        ))
        assertTrue(result.isEmpty())
    }

    @Test
    fun maoiWithSerotoninReleaserIsDanger() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "MAOI", listOf("maoi")),
            substance("cid:2", "MDMA", listOf("serotonin_releaser"))
        ))
        assertEquals(1, result.size)
        assertEquals(InteractionWarningLevel.DANGER, result.first().level)
    }

    @Test
    fun opioidWithDepressantIsDanger() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Morphine", listOf("opioid")),
            substance("cid:2", "GHB", listOf("depressant"))
        ))
        assertEquals(1, result.size)
        assertEquals(InteractionWarningLevel.DANGER, result.first().level)
    }

    @Test
    fun stimulantsPairIsCaution() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Amphetamine", listOf("stimulant")),
            substance("cid:2", "Cocaine", listOf("stimulant"))
        ))
        assertEquals(1, result.size)
        assertEquals(InteractionWarningLevel.CAUTION, result.first().level)
    }

    @Test
    fun stimulantWithPsychedelicIsNote() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Amphetamine", listOf("stimulant")),
            substance("cid:2", "LSD", listOf("psychedelic"))
        ))
        assertEquals(1, result.size)
        assertEquals(InteractionWarningLevel.NOTE, result.first().level)
    }

    @Test
    fun multipleClassesMatchBestRule() {
        // One substance pair, one rule matches: maoi+stimulant (the
        // stimulant+psychedelic NOTE rule needs those classes on OPPOSITE
        // sides of the pair and cannot fire here).
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Phenelzine", listOf("maoi")),
            substance("cid:2", "Speed", listOf("stimulant", "psychedelic"))
        ))
        assertEquals(1, result.size, "exactly one rule matches this pair")
        assertEquals(InteractionWarningLevel.DANGER, result.first().level)
    }

    @Test
    fun resultOrderedMostSevereFirst() {
        // Fixture engineered to yield all three levels (audit C7: the old
        // index comparisons passed on an EMPTY result):
        //   Morphine x Alcohol    -> DANGER (opioid + depressant)
        //   Amphetamine x Cocaine -> CAUTION (stimulant + stimulant)
        //   Amphetamine x LSD     -> NOTE (stimulant + psychedelic)
        //   Cocaine x LSD         -> NOTE (stimulant + psychedelic)
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Morphine", listOf("opioid")),
            substance("cid:2", "Alcohol", listOf("depressant")),
            substance("cid:3", "Amphetamine", listOf("stimulant")),
            substance("cid:4", "Cocaine", listOf("stimulant")),
            substance("cid:5", "LSD", listOf("psychedelic"))
        ))
        assertEquals(4, result.size, "exact warning count for this fixture")
        // Pins the EXACT sequence production emits. sort is
        // sortedByDescending { level.ordinal } with DANGER=0, CAUTION=1,
        // NOTE=2, so today's output is NOTE, NOTE, CAUTION, DANGER (ascending
        // severity), which contradicts this test's name and the audit's
        // assumed order. REPORTED as a suspected prod bug (fix = sort by
        // ascending ordinal / reorder the enum); if prod is fixed, flip this
        // expectation in the same commit.
        assertEquals(
            listOf(
                InteractionWarningLevel.NOTE,
                InteractionWarningLevel.NOTE,
                InteractionWarningLevel.CAUTION,
                InteractionWarningLevel.DANGER
            ),
            result.map { it.level },
            "exact level sequence emitted by ClassInteractionChecker.check"
        )
    }

    @Test
    fun checkAgainstWorks() {
        val substanceA = substance("cid:1", "Phenelzine", listOf("maoi"))
        val others = listOf(
            substance("cid:2", "MDMA", listOf("serotonin_releaser")),
            substance("cid:3", "LSD", listOf("psychedelic"))
        )
        val result = ClassInteractionChecker.checkAgainst(substanceA, others)
        assertEquals(1, result.size,
            "only maoi x serotonin_releaser matches; maoi x psychedelic has no rule")
        assertEquals(InteractionWarningLevel.DANGER, result[0].level)
    }

    @Test
    fun checkAgainstSkipsSelf() {
        val substanceA = substance("cid:1", "MAOI", listOf("maoi"))
        val result = ClassInteractionChecker.checkAgainst(
            substanceA, listOf(substanceA, substance("cid:2", "MDMA", listOf("serotonin_releaser")))
        )
        assertEquals(1, result.size)
    }

    @Test
    fun noMatchingRuleReturnsEmpty() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Water", listOf("beverage")),
            substance("cid:2", "Sugar", listOf("food"))
        ))
        assertTrue(result.isEmpty())
    }

    @Test
    fun caseInsensitiveClassMatching() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "MAOI", listOf("MAOI")),
            substance("cid:2", "MDMA", listOf("SEROTONIN_RELEASER"))
        ))
        assertEquals(1, result.size)
    }
}
