package app.journal.catalog

import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiDurationRoute
import app.journal.model.DoseWikiStage
import app.journal.model.DoseWikiStages
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

    /** Fingerprint of the loaded catalog, or null when the database cannot open. */
    fun fingerprint(): String? = runCatching {
        SubstanceCatalog.contentVersion()?.let { "db:$it" }
    }.getOrNull()

    /** Fingerprint from the tiny bundled manifest, readable without opening the database. */
    fun bundledFingerprint(): String? = runCatching {
        val text = readBundledResource("/manifest.json") ?: return null
        val version = AppJson.json.parseToJsonElement(text).jsonObject["content_version"]
            ?.jsonPrimitive?.content
        version?.takeIf { it.isNotBlank() }?.let { "db:$it" }
    }.getOrNull()

    fun loadSubstances(): List<Substance> =
        CatalogMapper.loadSubstances(currentTimeMillis(), SOURCE_VERSION)

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
