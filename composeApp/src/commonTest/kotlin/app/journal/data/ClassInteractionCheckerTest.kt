package app.journal.data

import app.journal.model.*
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
        // MAOI + stimulant is DANGER; also matches stimulant+psychedelic NOTE
        // Should return DANGER (highest severity)
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "Phenelzine", listOf("maoi")),
            substance("cid:2", "Speed", listOf("stimulant", "psychedelic"))
        ))
        assertTrue(result.isNotEmpty())
        assertEquals(InteractionWarningLevel.DANGER, result.first().level)
    }

    @Test
    fun resultOrderedMostSevereFirst() {
        val result = ClassInteractionChecker.check(listOf(
            substance("cid:1", "MDMA", listOf("serotonin_releaser")),
            substance("cid:2", "Adderall", listOf("stimulant")),
            substance("cid:3", "Alcohol", listOf("depressant")),
            substance("cid:4", "Morphine", listOf("opioid"))
        ))
        val levels = result.map { it.level }
        // Should be sorted DANGER before CAUTION before NOTE
        val dangerIdx = levels.indexOf(InteractionWarningLevel.DANGER)
        val cautionIdx = levels.indexOf(InteractionWarningLevel.CAUTION)
        val noteIdx = levels.indexOf(InteractionWarningLevel.NOTE)
        assertTrue(dangerIdx < cautionIdx || cautionIdx == -1, "DANGER should come before CAUTION")
        assertTrue(cautionIdx < noteIdx || noteIdx == -1, "CAUTION should come before NOTE")
    }

    @Test
    fun checkAgainstWorks() {
        val substanceA = substance("cid:1", "Phenelzine", listOf("maoi"))
        val others = listOf(
            substance("cid:2", "MDMA", listOf("serotonin_releaser")),
            substance("cid:3", "LSD", listOf("psychedelic"))
        )
        val result = ClassInteractionChecker.checkAgainst(substanceA, others)
        assertTrue(result.isNotEmpty())
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
