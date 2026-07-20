package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Molecular properties from PubChem.
 * Every pure compound gets a CID that uniquely identifies it at the chemical level.
 */
@Immutable
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
@Immutable
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
@Immutable
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
@Immutable
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
@Immutable
@Serializable
data class IupharInteraction(
    val targetId: Long? = null,
    val targetName: String? = null,
    val targetSpecies: String? = null,
    val type: String? = null,
    val action: String? = null,
    val affinity: String? = null,
    val affinityParameter: String? = null,
    val endogenous: Boolean? = null,
    val primaryTarget: Boolean? = null,
)

/**
 * IUPHAR/BPS Guide to PHARMACOLOGY pharmacology data.
 * Quantitative binding affinities at molecular targets.
 */
@Immutable
@Serializable
data class IupharData(
    val ligandId: Int? = null,
    val interactions: List<IupharInteraction> = emptyList(),
)

/**
 * Single binding affinity measurement from the PDSP Ki database.
 * Ki values are in nanomolar (nM). Lower values = stronger binding.
 */
@Immutable
@Serializable
data class PdspKiRecord(
    val targetName: String? = null,
    val gene: String? = null,
    val species: String? = null,
    val kiNanoMolar: Double? = null,
    val reference: String? = null,
    val source: String? = null,
)

/**
 * PDSP Ki database pharmacology data.
 * Contains Ki binding affinity measurements at CNS molecular targets.
 * The official PDSP Ki database is maintained by NIMH.
 */
@Immutable
@Serializable
data class PdspData(
    val records: List<PdspKiRecord> = emptyList(),
)

/**
 * Single binding affinity measurement from BindingDB.
 * Affinity values are in nanomolar (nM). Lower values = stronger binding.
 */
@Immutable
@Serializable
data class BindingdbRecord(
    val targetName: String? = null,
    val uniprotId: String? = null,
    val geneSymbol: String? = null,
    val species: String? = null,
    val affinityType: String? = null,
    val affinityNM: Double? = null,
    val pmid: String? = null,
)

/**
 * BindingDB pharmacology data.
 * Experimentally measured binding affinities (Ki, Kd, IC50, EC50)
 * at protein targets. Curated by the BindingDB project.
 */
@Immutable
@Serializable
data class BindingdbData(
    val records: List<BindingdbRecord> = emptyList(),
)

/**
 * A single binding affinity measurement from a Wikipedia pharmacology table.
 *
 * Wikipedia drug/pharmacology articles often include binding affinity tables
 * under the Pharmacology section, listing target receptors/proteins with
 * Ki, EC50, IC50, and Emax values. Values are extracted from wikicode tables
 * like "Activities of [substance]".
 */
@Immutable
@Serializable
data class WikipediaRecord(
    /** Cleaned target name (e.g. "5-HT2A", "SERT", "NMDA"). */
    val targetName: String? = null,
    /** Primary Ki value in nM (single value, not a range). */
    val kiNM: Double? = null,
    /** Minimum Ki in nM if the Ki is expressed as a range (e.g. "113-695"). */
    val kiNMMin: Double? = null,
    /** Maximum Ki in nM if the Ki is expressed as a range. */
    val kiNMMax: Double? = null,
    /** Operator for inequality Ki values (e.g. ">" for ">10,000"). */
    val kiNMOperator: String? = null,
    /** EC50 value in nM, if present. */
    val ec50NM: Double? = null,
    /** IC50 value in nM, if present. */
    val ic50NM: Double? = null,
    /** Maximal efficacy percentage (Emax), if present. */
    val emaxPercent: Double? = null,
    /** Species annotation if specified (e.g. "rat", "mouse"). */
    val species: String? = null,
    /** The raw text of the affinity cell for reference. */
    val rawText: String? = null,
)

/**
 * Wikipedia pharmacology data.
 *
 * Extracted from binding affinity tables in drug/pharmacology Wikipedia
 * articles. Each record represents the substance's interaction with one
 * molecular target (receptor, transporter, enzyme, ion channel).
 */
@Immutable
@Serializable
data class WikipediaData(
    val records: List<WikipediaRecord> = emptyList(),
)

@Immutable
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
    // --- IUPHAR/BPS pharmacology data ---
    val iupharData: IupharData? = null,
    // --- PDSP Ki database pharmacology data ---
    val pdspData: PdspData? = null,
    // --- BindingDB pharmacology data ---
    val bindingdbData: BindingdbData? = null,
    // --- Wikipedia pharmacology data ---
    val wikipediaData: WikipediaData? = null,
    val erowidUrl: String = "",
    /** Source attributions: "psychonautwiki", "pubchem", "tripsit", "wikidata", "chembl", "iuphar", "pdsp", "bindingdb" */
    val sources: List<String> = emptyList(),
    val cachedAt: Long,
    val sourceVersion: String,
    val userAnnotations: Map<String, String> = emptyMap(),
) : VaultDocument
