package app.journal.model.rules

import app.journal.model.Dose
import app.journal.model.Interaction
import app.journal.model.Medication
import app.journal.model.Substance
import app.journal.util.currentTimeMillis

/**
 * Interaction check over everything active right now: the open session's doses,
 * standing medications, and past doses that are still inside their elimination
 * window. [recentSubstanceNames] and [medicationNames] let the UI explain what was
 * included beyond the session itself.
 */
data class ActiveInteractionResult(
    val pairs: InteractionCheckResult = InteractionCheckResult(),
    val classWarnings: List<ClassBasedWarning> = emptyList(),
    val medicationNames: List<String> = emptyList(),
    val recentSubstanceNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !pairs.hasIssues && pairs.uncertain.isEmpty() && classWarnings.isEmpty()
}

/**
 * Builds the active set and runs both interaction checkers over it.
 *
 * A dose counts as still present while its age stays inside [ACTIVE_HALF_LIVES]
 * elimination half-lives, capped at [MAX_ACTIVE_DAYS]. A substance with no
 * half-life in the source is left out of that sweep: the check never claims an
 * active window it cannot compute.
 */
object ActiveInteractionCheck {

    const val ACTIVE_HALF_LIVES = 5.0
    const val MAX_ACTIVE_DAYS = 30.0

    private const val MINUTE_MS = 60_000.0

    /** Active window in minutes, or null when the source carries no half-life. */
    fun windowMinutes(substance: Substance): Double? {
        val halfLife = substance.halfLifeMinutes ?: return null
        if (!halfLife.isFinite() || halfLife <= 0.0) return null
        return (halfLife * ACTIVE_HALF_LIVES).coerceAtMost(MAX_ACTIVE_DAYS * 24 * 60)
    }

    fun isStillActive(dose: Dose, substance: Substance, now: Long): Boolean {
        val window = windowMinutes(substance) ?: return false
        val ageMinutes = (now - dose.timestamp) / MINUTE_MS
        return ageMinutes >= 0.0 && ageMinutes <= window
    }

    /**
     * @param ownDoseIds doses already shown as the session's own; excluded from the
     * recent sweep so the same dose is never counted twice.
     * @param excludeSessionId all persisted doses of this session stay out of the sweep.
     * @param now injectable clock, tests pin it.
     */
    fun run(
        sessionDoses: List<Dose>,
        medications: List<Medication>,
        allDoses: List<Dose>,
        ownDoseIds: Set<String>,
        excludeSessionId: String? = null,
        substancesById: Map<String, Substance>,
        interactions: List<Interaction>,
        now: Long = currentTimeMillis(),
    ): ActiveInteractionResult {
        val activeMedications = medications.filter { it.isActiveAt(now) }
        val sessionSubstances = sessionDoses.mapNotNull { substancesById[it.substanceId] }
        val recentDoses = allDoses.filter { dose ->
            dose.id !in ownDoseIds &&
                (excludeSessionId == null || dose.sessionId != excludeSessionId) &&
                substancesById[dose.substanceId]?.let { isStillActive(dose, it, now) } == true
        }
        val recentSubstances = recentDoses.mapNotNull { substancesById[it.substanceId] }

        val entries = buildEntries(sessionSubstances, recentSubstances, activeMedications)
        val pairIds = (sessionSubstances.map { it.id } + recentSubstances.map { it.id }).distinct()
        val pairs = if (pairIds.size >= 2) {
            InteractionChecker.checkPairwise(pairIds, interactions)
        } else {
            InteractionCheckResult()
        }
        return ActiveInteractionResult(
            pairs = pairs,
            classWarnings = ClassInteractionChecker.checkNamed(entries.values.toList()),
            medicationNames = activeMedications.map { it.displayName }.distinct().sorted(),
            recentSubstanceNames = recentSubstances.map { it.name }.distinct().sorted()
                .filterNot { name -> sessionSubstances.any { it.name == name } },
        )
    }

    /** One entry per display name, classes unioned so a logged and a standing copy never pair. */
    private fun buildEntries(
        sessionSubstances: List<Substance>,
        recentSubstances: List<Substance>,
        medications: List<Medication>,
    ): Map<String, NamedInteractionClasses> {
        val entries = linkedMapOf<String, NamedInteractionClasses>()
        fun add(name: String, classes: List<String>) {
            val key = name.trim().lowercase()
            val existing = entries[key]
            entries[key] = NamedInteractionClasses(
                name = existing?.name ?: name,
                classes = ((existing?.classes ?: emptyList()) + classes).distinct(),
            )
        }
        sessionSubstances.forEach { add(it.name, it.interactionClasses) }
        recentSubstances.forEach { add(it.name, it.interactionClasses) }
        medications.forEach { add(it.displayName, it.preset.interactionClasses) }
        return entries
    }
}
