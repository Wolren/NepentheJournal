package app.journal.model.rules

import app.journal.model.InteractionClasses
import app.journal.model.Substance

/**
 * Severity level for a class-based interaction warning.
 */
enum class InteractionWarningLevel { DANGER, CAUTION, NOTE }

/**
 * A single interaction warning between two substances.
 */
data class ClassBasedWarning(
    val level: InteractionWarningLevel,
    val substanceA: String,
    val substanceB: String,
    val message: String,
)

/** A display name plus the classes it contributes, for entries with no catalog row. */
data class NamedInteractionClasses(
    val name: String,
    val classes: List<String>,
)

/**
 * Deterministic interaction checker that operates on pharmacological classes
 * assigned to each substance. Ported from Field Notes' interactions.rs.
 *
 * Rules are common-knowledge harm-reduction categories. Every pair of
 * substances in a session is checked against the rule table.
 */
object ClassInteractionChecker {

    private val RULES = listOf(
        Rule("maoi", "serotonin_releaser", InteractionWarningLevel.DANGER,
            "MAOI + serotonin releaser (e.g. MDMA): high risk of serotonin syndrome and hypertensive crisis. Contraindicated."),
        Rule("maoi", "ssri", InteractionWarningLevel.DANGER,
            "MAOI + SSRI: serious serotonin syndrome risk. Long washout periods apply."),
        Rule("maoi", "serotonergic", InteractionWarningLevel.DANGER,
            "MAOI + serotonergic drug: serotonin syndrome risk."),
        Rule("maoi", "stimulant", InteractionWarningLevel.DANGER,
            "MAOI + stimulant: risk of hypertensive crisis."),
        Rule("maoi", "opioid", InteractionWarningLevel.DANGER,
            "MAOI + certain opioids (tramadol, meperidine, DXM): serotonin syndrome risk."),
        Rule("lithium", "psychedelic", InteractionWarningLevel.DANGER,
            "Lithium + psychedelic: reports of seizures and serious reactions. Contraindicated."),
        Rule("lithium", "stimulant", InteractionWarningLevel.DANGER,
            "Lithium + stimulant: increased seizure and neurotoxicity risk."),
        Rule("opioid", "benzodiazepine", InteractionWarningLevel.DANGER,
            "Opioid + benzodiazepine: additive respiratory depression. Frequently fatal in overdose."),
        Rule("opioid", "depressant", InteractionWarningLevel.DANGER,
            "Opioid + depressant (alcohol/GHB/etc.): additive respiratory depression. A leading cause of overdose."),
        Rule("depressant", "benzodiazepine", InteractionWarningLevel.CAUTION,
            "Depressant + benzodiazepine: additive sedation and blackout/respiratory risk."),
        Rule("benzodiazepine", "benzodiazepine", InteractionWarningLevel.CAUTION,
            "Multiple benzodiazepines stack unpredictably, with heightened sedation and memory loss."),
        Rule("ssri", "serotonin_releaser", InteractionWarningLevel.CAUTION,
            "SSRI + serotonin releaser (e.g. MDMA): serotonin syndrome risk, and SSRIs blunt the effect."),
        Rule("serotonergic", "serotonin_releaser", InteractionWarningLevel.CAUTION,
            "Serotonergic drug + serotonin releaser: serotonin syndrome risk. The serotonergic drug can also blunt the effect."),
        Rule("serotonin_releaser", "serotonin_releaser", InteractionWarningLevel.CAUTION,
            "Two serotonin releasers: additive serotonin syndrome and neurotoxicity risk."),
        Rule("stimulant", "stimulant", InteractionWarningLevel.CAUTION,
            "Two stimulants: additive cardiovascular strain (heart rate, blood pressure, temperature)."),
        Rule("dissociative", "depressant", InteractionWarningLevel.CAUTION,
            "Dissociative + depressant: additive sedation; nausea while sedated is a choke risk."),
        Rule("stimulant", "psychedelic", InteractionWarningLevel.NOTE,
            "Stimulant + psychedelic: can amplify anxiety and cardiovascular load."),
        Rule("stimulant", "dissociative", InteractionWarningLevel.NOTE,
            "Stimulant + dissociative: masks sedation and raises cardiovascular load."),
    )

    private data class Rule(
        val classA: String,
        val classB: String,
        val level: InteractionWarningLevel,
        val message: String,
    )

    /**
     * Check all substances against each other for class-based interactions.
     * Each substance should have its `interactionClasses` populated.
     * Precomputes lowercase class sets for O(1) containment checks.
     */
    fun check(substances: List<Substance>): List<ClassBasedWarning> =
        checkNamed(substances.map { NamedInteractionClasses(it.name, it.interactionClasses) })

    /**
     * Check named class entries against each other. Medications enter the check as
     * name plus preset classes, so they never need a catalog [Substance] row.
     */
    fun checkNamed(entries: List<NamedInteractionClasses>): List<ClassBasedWarning> {
        if (entries.size < 2) return emptyList()
        val classSets = entries.map { entry ->
            entry.classes.mapTo(mutableSetOf()) { it.lowercase() }
        }
        return scanPairs(entries.zip(classSets)) { _, (entry, setA), _, (other, setB) ->
            matchRuleWithSet(setA, setB)?.let { rule ->
                ClassBasedWarning(rule.level, entry.name, other.name, rule.message)
            }
        }.sortedByDescending { it.level.ordinal }
    }

    /**
     * Check a single substance against a list of others.
     */
    fun checkAgainst(substance: Substance, others: List<Substance>): List<ClassBasedWarning> {
        val combined = (listOf(substance) + others).distinctBy { it.id }
        val classSets = combined.map { sub ->
            sub.interactionClasses.mapTo(mutableSetOf()) { it.lowercase() }
        }
        return scanPairs(combined.zip(classSets)) { _, (subA, setA), _, (subB, setB) ->
            matchRuleWithSet(setA, setB)?.let { rule ->
                ClassBasedWarning(rule.level, subA.name, subB.name, rule.message)
            }
        }.sortedByDescending { it.level.ordinal }
    }

    private fun matchRuleWithSet(classesA: Set<String>, classesB: Set<String>): Rule? {
        return RULES.fold(null as Rule?) { best, rule ->
            val matches = (rule.classA in classesA && rule.classB in classesB) ||
                          (rule.classA in classesB && rule.classB in classesA)
            if (matches && (best == null || rule.level.ordinal > best.level.ordinal)) rule else best
        }
    }
}
