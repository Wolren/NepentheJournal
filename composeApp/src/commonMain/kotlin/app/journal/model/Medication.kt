package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Medication types a user can pick from. [interactionClasses] drive the class-based
 * checker and must stay inside [InteractionClasses.ALL]; [label] is display text.
 */
enum class MedicationPreset(
    val id: String,
    val label: String,
    val examples: String,
    val interactionClasses: List<String>,
) {
    SSRI(
        "ssri", "SSRI", "sertraline, fluoxetine, escitalopram",
        listOf("ssri", "serotonergic"),
    ),
    SNRI(
        "snri", "SNRI", "venlafaxine, duloxetine",
        listOf("serotonergic"),
    ),
    MAOI(
        "maoi", "MAOI", "phenelzine, tranylcypromine, moclobemide",
        listOf("maoi"),
    ),
    LITHIUM(
        "lithium", "Lithium", "lithium carbonate, lithium citrate",
        listOf("lithium"),
    ),
    BENZODIAZEPINE(
        "benzodiazepine", "Benzodiazepine", "alprazolam, diazepam, clonazepam",
        listOf("benzodiazepine"),
    ),
    OPIOID(
        "opioid", "Opioid", "codeine, oxycodone, buprenorphine",
        listOf("opioid"),
    ),
    STIMULANT(
        "stimulant", "Stimulant", "amphetamine, methylphenidate, lisdexamfetamine",
        listOf("stimulant"),
    ),
    SEROTONERGIC(
        "serotonergic", "Other serotonergic", "tramadol, trazodone, mirtazapine",
        listOf("serotonergic"),
    ),
    OTHER(
        "other", "Other", "no class checks for this type",
        emptyList(),
    );

    companion object {
        fun fromId(id: String): MedicationPreset =
            entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * A medication taken on a schedule, kept for the interaction check rather than the dose log.
 * [presetId] supplies the interaction classes; [startedAt] and [endedAt] decide when it counts.
 * Device-local: excluded from sync replication, like [Person].
 */
@Serializable
data class Medication(
    override val id: String,

    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val name: String,
    val presetId: String = MedicationPreset.OTHER.id,
    /** Free text with the amount inside, e.g. "50 mg daily". */
    val doseLabel: String? = null,
    val startedAt: Long,
    /** Null while the user still takes it; set when the course stops. */
    val endedAt: Long? = null,
    val notes: String? = null,
) : VaultDocument {

    val preset: MedicationPreset get() = MedicationPreset.fromId(presetId)

    /** Blank names fall back to the preset label so the check always has something to show. */
    val displayName: String get() = name.trim().ifBlank { preset.label }

    fun isActiveAt(now: Long): Boolean =
        startedAt <= now && (endedAt == null || endedAt > now)
}
