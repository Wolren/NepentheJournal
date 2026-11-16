package app.journal.catalog

import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiDurationRoute
import app.journal.model.DoseWikiStage
import app.journal.model.DoseWikiStages
import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.Substance
import app.journal.serde.AppJson
import app.journal.util.currentTimeMillis
import app.journal.util.readBundledResource
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One boundary between the app and the bundled catalog.
 * Everything here degrades to null when the database is absent, so a build
 * without the generated asset keeps the old JSON ingest path.
 */
object CatalogSource {

    /** One bundled reference source and the licence its rows ship under. */
    data class CatalogSourceInfo(val name: String, val licence: String, val url: String)

    /** content_version from the manifest text, the cache key for extracted database copies. */
    fun versionFrom(manifestText: String?): String? {
        val text = manifestText ?: return null
        return runCatching {
            AppJson.json.parseToJsonElement(text).jsonObject["content_version"]
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Fingerprint of the loaded catalog, or null when the database cannot open. */
    fun fingerprint(): String? = runCatching {
        SubstanceCatalog.contentVersion()?.let { "db:$it" }
    }.getOrNull()

    /** Fingerprint from the tiny bundled manifest, or null when this target cannot open the catalog. */
    fun bundledFingerprint(): String? = if (CatalogDriver.supported) cachedFingerprint else null

    private val cachedFingerprint: String? by lazy {
        runCatching {
            val text = readBundledResource("/manifest.json") ?: return@runCatching null
            val version = AppJson.json.parseToJsonElement(text).jsonObject["content_version"]
                ?.jsonPrimitive?.content
            version?.takeIf { it.isNotBlank() }?.let { "db:$it" }
        }.getOrNull()
    }

    fun loadSubstances(): List<Substance> =
        CatalogMapper.loadSubstances(currentTimeMillis(), SOURCE_VERSION)

    fun loadEffects(): List<Effect> = CatalogMapper.loadEffects(currentTimeMillis())

    fun loadInteractions(): List<Interaction> = CatalogMapper.loadInteractions(currentTimeMillis())

    /** Field name to the sources that supplied it, empty for user-created rows. */
    fun provenanceFor(substanceId: String): Map<String, List<String>> = runCatching {
        SubstanceCatalog.database.catalogQueries.selectFieldSources(substanceId) { field, source ->
            field to source
        }.executeAsList().groupBy({ it.first }, { it.second })
    }.getOrDefault(emptyMap())

    /**
     * Inverts provenance to one entry per source, so a source that feeds several
     * fields is named once. Sort: most fields first, then name.
     */
    fun sourcesToFields(provenance: Map<String, List<String>>): List<Pair<String, List<String>>> =
        provenance.entries
            .flatMap { (field, sources) -> sources.map { it to field } }
            .groupBy({ it.first }, { it.second })
            .map { (source, fields) -> source to fields.distinct().sorted() }
            .sortedWith(compareByDescending<Pair<String, List<String>>> { it.second.size }.thenBy { it.first })

    /** Every bundled source with its licence, for the in-app attribution list. */
    fun sources(): List<CatalogSourceInfo> = runCatching {
        SubstanceCatalog.database.catalogQueries.selectSources { name, licence, url ->
            CatalogSourceInfo(name, licence, url)
        }.executeAsList()
    }.getOrDefault(emptyList())

    /** Structured duration for a substance name, the shape [DoseWikiDuration] callers expect. */
    fun durationFor(name: String): DoseWikiDuration? {
        val substance = try {
            SubstanceCatalog.byName(name)
        } catch (e: Exception) {
            null
        } ?: return null
        val stages = substance.durationStages
        if (stages.isEmpty()) return null
        val routes = stages.groupBy { it.route }.map { (route, rows) ->
            val byStage = rows.associateBy { it.stage }
            DoseWikiDurationRoute(
                route = route,
                stages = DoseWikiStages(
                    onset = byStage["onset"]?.toStage(),
                    come_up = byStage["come_up"]?.toStage(),
                    peak = byStage["peak"]?.toStage(),
                    offset = byStage["offset"]?.toStage(),
                    after_effects = byStage["after_effects"]?.toStage(),
                    total_duration = byStage["total_duration"]?.toStage(),
                ),
            )
        }
        return DoseWikiDuration(routes = routes)
    }

    private fun CatalogDurationStage.toStage(): DoseWikiStage =
        DoseWikiStage(min = min, max = max, unit = unit)

    private const val SOURCE_VERSION = "catalog"
}
