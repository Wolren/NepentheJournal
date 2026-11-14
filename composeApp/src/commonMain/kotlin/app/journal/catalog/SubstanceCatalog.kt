package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.journal.catalog.db.CatalogDatabase
import app.journal.catalog.db.Substance as CatalogRow

/** Opens the bundled substance catalog read-only. */
expect object CatalogDriver {

    fun open(): SqlDriver

    /** False on a target whose driver is not wired yet, so the app falls back to the JSON ingest. */
    val supported: Boolean
}

/** One dose tier for one route, as stored in the catalog. */
data class CatalogDoseRange(
    val route: String,
    val routeOrder: Long,
    val tier: String,
    val min: Double?,
    val max: Double?,
    val unit: String?,
)

/** One duration stage for one route, in the source's own unit. */
data class CatalogDurationStage(
    val route: String,
    val routeOrder: Long,
    val stage: String,
    val min: Double?,
    val max: Double?,
    val unit: String?,
)

/** A substance as the catalog stores it, with its child rows loaded. */
data class CatalogSubstance(
    val id: String,
    val name: String,
    val summary: String?,
    val halfLifeMinutes: Double?,
    val tmaxMinutes: Double?,
    val aliases: List<String>,
    val classes: List<String>,
    val routes: List<String>,
    val doseRanges: List<CatalogDoseRange>,
    val durationStages: List<CatalogDurationStage>,
    val effects: List<String>,
    val identifiers: Map<String, String>,
)

/**
 * Read-only access to the bundled substance catalog.
 * The database ships with the app; nothing here writes or migrates it.
 */
object SubstanceCatalog {

    internal val database: CatalogDatabase by lazy { CatalogDatabase(CatalogDriver.open()) }

    fun contentVersion(): String? =
        database.catalogQueries.contentVersion().executeAsOneOrNull()

    fun count(): Long = database.catalogQueries.countSubstances().executeAsOne()

    fun byId(id: String): CatalogSubstance? =
        database.catalogQueries.selectById(id).executeAsOneOrNull()?.toSubstance()

    fun byName(name: String): CatalogSubstance? =
        database.catalogQueries.selectByName(name).executeAsOneOrNull()?.toSubstance()

    fun search(query: String, limit: Int = 50): List<CatalogSubstance> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return database.catalogQueries.selectByNameOrAlias(needle, needle)
            .executeAsList()
            .take(limit)
            .map { it.toSubstance() }
    }

    private fun CatalogRow.toSubstance(): CatalogSubstance {
        val queries = database.catalogQueries
        return CatalogSubstance(
            id = id,
            name = name,
            summary = summary,
            halfLifeMinutes = half_life_minutes,
            tmaxMinutes = tmax_minutes,
            aliases = queries.selectAliases(id).executeAsList(),
            classes = queries.selectClasses(id).executeAsList(),
            routes = queries.selectRoutes(id).executeAsList(),
            doseRanges = queries.selectDoseRanges(id).executeAsList().map {
                CatalogDoseRange(it.route, it.route_order, it.tier, it.min, it.max, it.unit)
            },
            durationStages = queries.selectDurationStages(id).executeAsList().map {
                CatalogDurationStage(it.route, it.route_order, it.stage, it.min, it.max, it.unit)
            },
            effects = queries.selectEffects(id).executeAsList().map { it.name },
            identifiers = queries.selectIdentifiers(id).executeAsList()
                .associate { it.scheme to it.ident_value },
        )
    }
}

