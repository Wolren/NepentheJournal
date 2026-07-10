package app.journal.data

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

/**
 * Deterministic interaction checker that operates on pharmacological classes
 * assigned to each substance. Ported from Field Notes' interactions.rs.
 *
 * Rules are common-knowledge harm-reduction categories. Every pair of
 * substances in a session is checked against the rule table.
 */
object ClassInteractionChecker {

    // (classA, classB, severity, message)
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
        Rule("opioid", "depressant", InteractionWarningLevel.DANGER,
            "Opioid + depressant (alcohol/GHB/etc.): additive respiratory depression — a leading cause of overdose."),
        Rule("opioid", "benzodiazepine", InteractionWarningLevel.DANGER,
            "Opioid + benzodiazepine: additive respiratory depression. Frequently fatal in overdose."),
        Rule("depressant", "benzodiazepine", InteractionWarningLevel.CAUTION,
            "Depressant + benzodiazepine: additive sedation and blackout/respiratory risk."),
        Rule("benzodiazepine", "benzodiazepine", InteractionWarningLevel.CAUTION,
            "Multiple benzodiazepines stack unpredictably — heightened sedation and memory loss."),
        Rule("ssri", "serotonin_releaser", InteractionWarningLevel.CAUTION,
            "SSRI + serotonin releaser (e.g. MDMA): serotonin syndrome risk, and SSRIs blunt the effect."),
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
     */
    fun check(substances: List<Substance>): List<ClassBasedWarning> {
        if (substances.size < 2) return emptyList()

        val result = mutableListOf<ClassBasedWarning>()

        for (i in substances.indices) {
            for (j in i + 1 until substances.size) {
                val a = substances[i]
                val b = substances[j]
                val classesA = a.interactionClasses.map { it.lowercase() }
                val classesB = b.interactionClasses.map { it.lowercase() }

                var best: Rule? = null
                for (rule in RULES) {
                    val matches = (rule.classA in classesA && rule.classB in classesB) ||
                                  (rule.classA in classesB && rule.classB in classesA)
                    if (matches && (best == null || rule.level.ordinal > best.level.ordinal)) {
                        best = rule
                    }
                }

                if (best != null) {
                    result.add(ClassBasedWarning(
                        level = best.level,
                        substanceA = a.name,
                        substanceB = b.name,
                        message = best.message,
                    ))
                }
            }
        }

        // Sort most severe first
        result.sortByDescending { it.level.ordinal }
        return result
    }

    /**
     * Check a single substance against a list of others.
     */
    fun checkAgainst(substance: Substance, others: List<Substance>): List<ClassBasedWarning> {
        val combined = listOf(substance) + others.filter { it.id != substance.id }
        return check(combined.distinctBy { it.id })
    }
}
