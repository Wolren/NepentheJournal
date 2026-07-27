package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Wrapper for all pharmacology data sources on a Substance.
 * Null when no pharmacology data is available - replaces 5 separate nullable fields.
 */
@Serializable
data class PharmacologySources(
    val chembl: ChemblData? = null,
    val iuphar: IupharData? = null,
    val pdsp: PdspData? = null,
    val bindingdb: BindingdbData? = null,
    val wikipedia: WikipediaData? = null,
)

/** Convenience accessor that groups all pharmacology data sources. */
val Substance.pharmacologySources: PharmacologySources?
    get() {
        val c = chemblData ?: iupharData ?: pdspData ?: bindingdbData ?: wikipediaData
        return if (c != null) PharmacologySources(
            chembl = chemblData, iuphar = iupharData,
            pdsp = pdspData, bindingdb = bindingdbData,
            wikipedia = wikipediaData
        ) else null
    }
