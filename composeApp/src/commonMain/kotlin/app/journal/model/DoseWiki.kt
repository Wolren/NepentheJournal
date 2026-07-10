package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Slimmed DoseWiki substance data, deserialized directly from the bundled
 * dosewiki_slim.json. This is the PRIMARY source for subjective effect
 * descriptions. All other fields are supplementary to the PsychonautWiki seed.
 *
 * DoseWiki content is CC0 public domain. See https://dose.wiki
 */
@Serializable
data class DoseWikiSubstance(
    val title: String,
    val slug: String? = null,
    val id: Long? = null,
    val summary: String? = null,
    val identification: DoseWikiIdentification? = null,
    val classification: DoseWikiClassification? = null,
    val dosage: DoseWikiDosage? = null,
    val duration: DoseWikiDuration? = null,
    val subjective_effects: DoseWikiSubjectiveEffects? = null,
    val interactions: DoseWikiInteractions? = null,
    val pharmacology: DoseWikiPharmacology? = null,
    val harm_potential: DoseWikiHarmPotential? = null,
    val tolerance: DoseWikiTolerance? = null,
    val legality: DoseWikiLegality? = null,
    val citations: List<DoseWikiCitation>? = null,
    val reagent_testing: Map<String, String>? = null,
)

@Serializable
data class DoseWikiIdentification(
    val alternative_names: List<String>? = null,
    val iupac_name: String? = null,
    val cas_number: String? = null,
    val smiles: String? = null,
    val inchi_key: String? = null,
    val molecular_formula: String? = null,
    val molecular_weight: String? = null,
    val common_name: String? = null,
)

@Serializable
data class DoseWikiClassification(
    val chemical_class: List<String>? = null,
    val psychoactive_class: List<String>? = null,
)

@Serializable
data class DoseWikiDosage(
    val routes: List<DoseWikiRoute>? = null,
)

@Serializable
data class DoseWikiRoute(
    val route: String? = null,
    val dose_ranges: DoseWikiDoseRanges? = null,
    val notes: String? = null,
)

@Serializable
data class DoseWikiDoseRanges(
    val threshold: DoseWikiRange? = null,
    val light: DoseWikiRange? = null,
    val moderate: DoseWikiRange? = null,
    val strong: DoseWikiRange? = null,
    val heavy: DoseWikiRange? = null,
)

@Serializable
data class DoseWikiRange(
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
)

@Serializable
data class DoseWikiDuration(
    val routes: List<DoseWikiDurationRoute>? = null,
)

@Serializable
data class DoseWikiDurationRoute(
    val route: String? = null,
    val stages: DoseWikiStages? = null,
    val half_life: String? = null,
    val half_life_notes: String? = null,
)

@Serializable
data class DoseWikiStages(
    val onset: DoseWikiStage? = null,
    val come_up: DoseWikiStage? = null,
    val peak: DoseWikiStage? = null,
    val offset: DoseWikiStage? = null,
    val after_effects: DoseWikiStage? = null,
    val total_duration: DoseWikiStage? = null,
)

@Serializable
data class DoseWikiStage(
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
)

/**
 * Per-substance subjective effects — the PRIMARY data source for Nepenthe's
 * local wiki. Each substance has effects organized by domain (cognitive,
 * physical, sensory) with names, descriptions, and category notes.
 */
@Serializable
data class DoseWikiSubjectiveEffects(
    val attribution: DoseWikiAttribution? = null,
    val cognitive: Map<String, DoseWikiEffectGroup>? = null,
    val physical: Map<String, DoseWikiEffectGroup>? = null,
    val sensory: DoseWikiSensory? = null,
    val notes: DoseWikiEffectNotes? = null,
)

@Serializable
data class DoseWikiAttribution(
    val author: String? = null,
    val text: String? = null,
    val url: String? = null,
)

@Serializable
data class DoseWikiEffectGroup(
    val effects: List<DoseWikiEffect>? = null,
    val note: String? = null,
)

@Serializable
data class DoseWikiEffect(
    val name: String,
    val description: String? = null,
)

@Serializable
data class DoseWikiSensory(
    val auditory: DoseWikiSensoryCategory? = null,
    val gustatory: DoseWikiSensoryCategory? = null,
    val tactile: DoseWikiSensoryCategory? = null,
    val visual: DoseWikiSensoryCategory? = null,
    val olfactory: DoseWikiSensoryCategory? = null,
    val multisensory: DoseWikiSensoryCategory? = null,
)

@Serializable
data class DoseWikiSensoryCategory(
    val note: String? = null,
    val subcategories: Map<String, DoseWikiEffectGroup>? = null,
)

@Serializable
data class DoseWikiEffectNotes(
    val overview: String? = null,
    val cognitive: String? = null,
    val physical: String? = null,
    val sensory: String? = null,
)

@Serializable
data class DoseWikiInteractions(
    val dangerous: List<String>? = null,
    val unsafe: List<String>? = null,
    val caution: List<String>? = null,
)

@Serializable
data class DoseWikiInteraction(
    val name: String,
    val reason: String? = null,
    val severity: String, // "danger" | "caution" | "note"
)

@Serializable
data class DoseWikiPharmacology(
    val pharmacodynamics: String? = null,
    val pharmacokinetics: String? = null,
    val metabolites: List<String>? = null,
    val receptor_profile: List<DoseWikiReceptor>? = null,
)

@Serializable
data class DoseWikiReceptor(
    val name: String? = null,
    val activity: String? = null,
)

@Serializable
data class DoseWikiHarmPotential(
    val addiction: DoseWikiHarmItem? = null,
    val psychosis: DoseWikiHarmItem? = null,
    val seizure: DoseWikiHarmItem? = null,
    val toxicity: DoseWikiToxicity? = null,
)

@Serializable
data class DoseWikiHarmItem(
    val description: String? = null,
    val level: String? = null,
)

@Serializable
data class DoseWikiToxicity(
    val organ_toxicity: List<DoseWikiOrganToxicity>? = null,
)

@Serializable
data class DoseWikiOrganToxicity(
    val system: String? = null,
    val findings: String? = null,
)

@Serializable
data class DoseWikiTolerance(
    val cross_tolerance: List<String>? = null,
    val full_tolerance: String? = null,
    val half_tolerance: String? = null,
    val baseline_tolerance: String? = null,
)

@Serializable
data class DoseWikiLegality(
    val countries: Map<String, DoseWikiLegalStatus>? = null,
    val international: List<String>? = null,
)

@Serializable
data class DoseWikiLegalStatus(
    val status: String? = null,
    val notes: String? = null,
)

@Serializable
data class DoseWikiCitation(
    val name: String? = null,
    val url: String? = null,
)

/**
 * Pharmacological interaction classes used by the deterministic checker.
 * Each substance can have multiple classes assigned.
 */
object InteractionClasses {
    val ALL = listOf(
        "maoi",
        "ssri",
        "serotonin_releaser",
        "serotonergic",
        "stimulant",
        "depressant",
        "benzodiazepine",
        "opioid",
        "psychedelic",
        "dissociative",
        "lithium",
        "cannabinoid",
        "deliriant",
    )

    val CLASS_LABELS = mapOf(
        "maoi" to "MAOI",
        "ssri" to "SSRI",
        "serotonin_releaser" to "Serotonin Releaser",
        "serotonergic" to "Serotonergic",
        "stimulant" to "Stimulant",
        "depressant" to "Depressant",
        "benzodiazepine" to "Benzodiazepine",
        "opioid" to "Opioid",
        "psychedelic" to "Psychedelic",
        "dissociative" to "Dissociative",
        "lithium" to "Lithium",
        "cannabinoid" to "Cannabinoid",
        "deliriant" to "Deliriant",
    )
}
