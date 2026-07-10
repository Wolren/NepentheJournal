package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Molecular properties from PubChem.
 * Every pure compound gets a CID that uniquely identifies it at the chemical level.
 */
@Serializable
data class ChemicalProperties(
    val cid: Long,
    val molecularFormula: String? = null,
    val molecularWeight: String? = null,
    val smiles: String? = null,
    val inchiKey: String? = null,
    val iupacName: String? = null,
    val xlogP: Double? = null,
    val hBondDonorCount: Int? = null,
    val hBondAcceptorCount: Int? = null,
)

/**
 * Cross-database identifiers from Wikidata.
 * Links this substance to ChEMBL, DrugBank, IUPHAR, and other pharmacological databases.
 */
@Serializable
data class WikidataRefs(
    val qid: String? = null,
    val chemblId: String? = null,
    val drugbankId: String? = null,
    val iupharId: String? = null,
    val chemspiderId: String? = null,
    val unii: String? = null,
    val chebiId: String? = null,
    val atcCode: String? = null,
)

/**
 * Single bioactivity measurement from ChEMBL.
 */
@Serializable
data class Bioactivity(
    val type: String, // IC50, Ki, EC50, Kd
    val value: Double? = null,
    val units: String? = null,
    val relation: String? = null,
    val targetId: String? = null,
    val assay: String? = null,
)

/**
 * ChEMBL molecule data including bioactivities against molecular targets.
 */
@Serializable
data class ChemblData(
    val prefName: String? = null,
    val mw: Double? = null,
    val alogp: Double? = null,
    val psa: Double? = null,
    val ro5Violations: Int? = null,
    val maxPhase: Int? = null,
    val bioactivities: List<Bioactivity> = emptyList(),
)

/**
 * Conflict: REMOTE_SOURCE_WINS + preserve userAnnotations.
 *
 * Primary identifier strategy:
 * - Pure compounds use "cid:{PubChem CID}" as ID (e.g. "cid:5761" for LSD)
 * - Plants/mixtures without a single CID use "pwiki:{name}" fallback
 *
 * The oldId field preserves the previous pwiki: name for migration.
 */
@Serializable
data class Substance(
    override val id: String,
    /** Previous ID (e.g. "pwiki:lsd") - kept for migration of session references. */
    val oldId: String? = null,
    /** PubChem Compound ID (CID). Null for plants/mixtures without a single compound. */
    val cid: Long? = null,
    override val docType: String = "substance",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    val pwikiId: String? = null,
    val name: String,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val substanceClass: List<String> = emptyList(),
    val routesOfAdministration: List<String> = emptyList(),
    val dosageBands: Map<String, String> = emptyMap(),
    val durationProfile: Map<String, String> = emptyMap(),
    val addictionPotential: String? = null,
    val toxicity: List<String> = emptyList(),
    val crossTolerances: List<String> = emptyList(),
    val effects: List<String> = emptyList(),
    val interactionClasses: List<String> = emptyList(),
    val chemicalProperties: ChemicalProperties? = null,
    // --- Wikidata cross-references ---
    val chemblId: String? = null,
    val drugbankId: String? = null,
    val iupharId: String? = null,
    val chemspiderId: String? = null,
    val unii: String? = null,
    val chebiId: String? = null,
    val atcCode: String? = null,
    // --- ChEMBL bioactivity data ---
    val chemblData: ChemblData? = null,
    val erowidUrl: String = "",
    /** Source attributions: "psychonautwiki", "pubchem", "tripsit", "wikidata", "chembl" */
    val sources: List<String> = emptyList(),
    val cachedAt: Long,
    val sourceVersion: String,
    val userAnnotations: Map<String, String> = emptyMap(),
) : VaultDocument
