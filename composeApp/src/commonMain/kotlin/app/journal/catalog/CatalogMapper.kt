package app.journal.catalog

import app.journal.ingest.SubstanceClassNormalizer
import app.journal.log.StartupClock
import app.journal.model.BindingdbData
import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.ChemblData
import app.journal.model.ChemicalProperties
import app.journal.model.IupharData
import app.journal.model.PdspData
import app.journal.model.Substance
import app.journal.model.WikipediaData
import app.journal.serde.AppJson
import kotlinx.serialization.Serializable

/**
 * Projects the catalog database into the model rows the app already uses.
 * Formatting matches the old ingest, so a migrated install renders identically.
 */
internal object CatalogMapper {

    fun loadSubstances(now: Long, sourceVersion: String): List<Substance> {
        val queries = SubstanceCatalog.database.catalogQueries
        StartupClock.mark("catalog driver ready")
        val rows = queries.selectAllSubstances {
                id, oldId, name, summary, cid, pwikiId, addiction, halfLife, tmax, _, _, payload ->
            RawSubstance(id, oldId, name, summary, cid, pwikiId, addiction, halfLife, tmax, payload)
        }.executeAsList()
        StartupClock.mark("catalog substance rows ${rows.size}")

        val aliases = queries.selectAllAliases { substanceId, alias -> substanceId to alias }
            .executeAsList().groupBy({ it.first }, { it.second })
        val classes = queries.selectAllClasses { substanceId, label -> substanceId to label }
            .executeAsList().groupBy({ it.first }, { it.second })
        val routes = queries.selectAllRoutes { substanceId, route -> substanceId to route }
            .executeAsList().groupBy({ it.first }, { it.second })
        val doses = queries.selectAllDoseRanges { substanceId, route, order, tier, min, max, unit ->
            substanceId to CatalogDoseRange(route, order, tier, min, max, unit)
        }.executeAsList().groupBy({ it.first }, { it.second })
        val durations = queries.selectAllDurationStages { substanceId, route, order, stage, min, max, unit ->
            substanceId to CatalogDurationStage(route, order, stage, min, max, unit)
        }.executeAsList().groupBy({ it.first }, { it.second })
        val effects = queries.selectAllEffects { substanceId, name -> substanceId to name }
            .executeAsList().groupBy({ it.first }, { it.second })
        val identifiers = queries.selectAllIdentifiers { substanceId, scheme, value ->
            Triple(substanceId, scheme, value)
        }.executeAsList().groupBy({ it.first }, { it.second to it.third })
        StartupClock.mark("catalog queries done")

        // Row mapping is pure and per-row, but it stays SEQUENTIAL: measured on the
        // 9.4 MB payload of the shipped catalog, every threaded shape lost to one
        // pass. 8-lane ForkJoin, an 8-thread chunked pool and the common-pool stream
        // all ran 235-270 ms against 110-125 ms single-threaded (4 GB heap too, so
        // it is not GC), and loadSubstances() fell from ~340 ms to ~120 ms. The
        // decode allocates too densely to scale; do not re-parallelise it without
        // re-measuring.
        return rows.map { raw ->
            val substanceDoses = doses[raw.id].orEmpty()
            val substanceDurations = durations[raw.id].orEmpty()
            val substanceIdentifiers = identifiers[raw.id].orEmpty().toMap()
            val payload = decodePayload(raw.payload)
            Substance(
                id = raw.id,
                oldId = raw.oldId,
                cid = raw.cid,
                createdAt = now,
                updatedAt = now,
                deviceOrigin = "system",
                pwikiId = raw.pwikiId,
                name = raw.name,
                aliases = aliases[raw.id].orEmpty(),
                summary = raw.summary,
                substanceClass = SubstanceClassNormalizer.normalize(classes[raw.id].orEmpty()).distinct(),
                routesOfAdministration = routes[raw.id].orEmpty(),
                dosageBands = dosageBands(substanceDoses),
                durationProfile = durationProfile(substanceDurations),
                halfLifeMinutes = raw.halfLife,
                tmaxMinutes = raw.tmax,
                addictionPotential = raw.addiction,
                toxicity = emptyList(),
                crossTolerances = emptyList(),
                effects = effects[raw.id].orEmpty(),
                interactionClasses = emptyList(),
                chemicalProperties = payload?.chemicalProperties,
                drugbankId = substanceIdentifiers["drugbank"],
                dosewikiSlug = substanceIdentifiers["dosewiki"],
                unii = substanceIdentifiers["unii"],
                atcCode = substanceIdentifiers["atc"],
                chemblData = payload?.chemblData,
                iupharData = payload?.iupharData,
                pdspData = payload?.pdspData,
                bindingdbData = payload?.bindingdbData,
                wikipediaData = payload?.wikipediaData,
                sources = listOf(sourceVersion),
                cachedAt = now,
                sourceVersion = sourceVersion,
            )
        }
    }

    /** Reference effects keyed exactly as the old ingest keyed them, so ids stay stable. */
    fun loadEffects(now: Long): List<Effect> {
        val rows = SubstanceCatalog.database.catalogQueries.selectAllReferenceEffects {
                substanceId, name, category, description ->
            Effect(
                id = "effect:dw:$substanceId:${name.hashCode().toLong() and 0x7FFFFFFF}",
                createdAt = now,
                updatedAt = now,
                name = name,
                description = description,
                category = category,
                substanceIds = listOf(substanceId),
            )
        }.executeAsList()
        return rows.distinctBy { it.id }
    }

    fun loadInteractions(now: Long): List<Interaction> {
        val rows = SubstanceCatalog.database.catalogQueries.selectAllReferenceInteractions {
                id, a, b, risk, description, sources ->
            Interaction(
                id = id,
                createdAt = now,
                updatedAt = now,
                substanceAId = a,
                substanceBId = b,
                riskLevel = riskOf(risk),
                description = description,
                sources = decodeSources(sources),
            )
        }.executeAsList()
        return rows
    }

    private fun riskOf(raw: String): InteractionRisk =
        InteractionRisk.entries.firstOrNull { it.name == raw } ?: InteractionRisk.UNKNOWN

    private fun decodeSources(raw: String): List<String> =
        runCatching { AppJson.json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private data class RawSubstance(
        val id: String,
        val oldId: String?,
        val name: String,
        val summary: String?,
        val cid: Long?,
        val pwikiId: String?,
        val addiction: String?,
        val halfLife: Double?,
        val tmax: Double?,
        val payload: String?,
    )

    /**
     * The substance payload as stored: pharmacology blocks keyed by field name. One
     * typed pass decodes 9.6 MB of payload; the tree-then-decode path cost twice that.
     */
    @Serializable
    private data class CatalogPayload(
        val chemicalProperties: ChemicalProperties? = null,
        val chemblData: ChemblData? = null,
        val iupharData: IupharData? = null,
        val pdspData: PdspData? = null,
        val bindingdbData: BindingdbData? = null,
        val wikipediaData: WikipediaData? = null,
    )

    /** The primary route is oral when present, else the earliest route in the source file. */
    private fun primaryRoute(orders: List<Pair<String, Long>>): String? {
        orders.firstOrNull { it.first.equals("oral", ignoreCase = true) }?.let { return it.first }
        return orders.minByOrNull { it.second }?.first
    }

    private fun dosageBands(ranges: List<CatalogDoseRange>): Map<String, String> {
        val route = primaryRoute(ranges.map { it.route to it.routeOrder }) ?: return emptyMap()
        return buildMap {
            for (range in ranges.filter { it.route == route }) {
                format(range.min, range.max, range.unit)?.let { put(bandKey(range.tier), it) }
            }
        }
    }

    private fun durationProfile(stages: List<CatalogDurationStage>): Map<String, String> {
        val route = primaryRoute(stages.map { it.route to it.routeOrder }) ?: return emptyMap()
        return buildMap {
            for (stage in stages.filter { it.route == route }) {
                format(stage.min, stage.max, stage.unit)?.let { put(stageKey(stage.stage), it) }
            }
        }
    }

    private fun bandKey(tier: String): String = if (tier == "moderate") "common" else tier

    private fun stageKey(stage: String): String = when (stage) {
        "come_up" -> "comeup"
        "after_effects" -> "afterglow"
        "total_duration" -> "total"
        else -> stage
    }

    /** "75-150 µg" and "10 µg", the shape the old ingest wrote into the model. */
    private fun format(min: Double?, max: Double?, unit: String?): String? {
        if (min == null) return null
        val suffix = unit?.trim()?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return if (max != null && max != min) "${number(min)}-${number(max)}$suffix"
        else "${number(min)}$suffix"
    }

    private fun number(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()

    private fun decodePayload(raw: String?): CatalogPayload? {
        if (raw.isNullOrBlank()) return null
        return runCatching { AppJson.json.decodeFromString<CatalogPayload>(raw) }.getOrNull()
    }
}
