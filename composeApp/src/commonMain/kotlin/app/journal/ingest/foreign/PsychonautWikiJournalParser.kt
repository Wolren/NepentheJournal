package app.journal.ingest.foreign

import app.journal.model.StomachFullness
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * PsychonautWiki Journal export, and PsyLog, which writes the same shape.
 * Layout: `experiences[].ingestions[]`, plus `customUnits[]` for unit aliases.
 */
internal object PsychonautWikiJournalParser {

    fun matches(root: JsonElement): Boolean {
        root.asObject()?.let { return it["experiences"] is JsonArray }
        val array = root as? JsonArray ?: return false
        return array.any { it.asObject()?.containsKey("ingestions") == true }
    }

    fun parse(root: JsonElement, tz: TimeZone): ForeignJournal {
        val experiences = ((root as? JsonArray) ?: root.asObject()?.get("experiences") as? JsonArray)
            ?: JsonArray(emptyList())
        val customUnits = customUnitNames(root)
        val parsed = experiences.mapNotNull { element ->
            val experience = element.asObject() ?: return@mapNotNull null
            val ingestions = (experience["ingestions"] as? JsonArray) ?: JsonArray(emptyList())
            val doses = ingestions
                .mapNotNull { ingestion(it.asObject() ?: return@mapNotNull null, customUnits, tz) }
                .sortedBy { it.timestamp }
            if (doses.isEmpty()) return@mapNotNull null
            ForeignExperience(
                title = experience.str("title"),
                notes = experience.str("text"),
                doses = doses,
            )
        }.sortedBy { it.doses.first().timestamp }
        return ForeignJournal(ForeignFormat.PSYCHONAUTWIKI, parsed)
    }

    private fun ingestion(ingestion: JsonObject, customUnits: Map<Int, String>, tz: TimeZone): ForeignDose? {
        val substance = ingestion.str("substanceName")
            ?: ingestion.num("customUnitId")?.toInt()?.let { customUnits[it] }
            ?: return null
        val amount = ingestion.num("dose") ?: return null
        if (amount <= 0.0) return null
        val timestamp = parseTimestamp(ingestion.str("time"), tz) ?: return null
        return ForeignDose(
            substance = substance,
            amount = amount,
            unit = RouteNames.unit(ingestion.str("units")),
            route = RouteNames.normalize(ingestion.str("administrationRoute")),
            timestamp = timestamp,
            notes = ingestion.str("notes"),
            isEstimate = ingestion.boolean("isDoseAnEstimate"),
            consumer = ingestion.str("consumerName"),
            stomachFullness = stomach(ingestion.str("stomachFullness")),
        )
    }

    /** PW spells them EMPTY, LIGHT, MODERATE, FULL. */
    private fun stomach(raw: String?): StomachFullness? =
        StomachFullness.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

    /** customUnitId -> the substance the alias belongs to. */
    private fun customUnitNames(root: JsonElement): Map<Int, String> {
        val units = root.asObject()?.get("customUnits") as? JsonArray ?: return emptyMap()
        return units.mapNotNull { element ->
            val unit = element.asObject() ?: return@mapNotNull null
            val id = unit.num("id")?.toInt() ?: return@mapNotNull null
            val name = unit.str("substanceName") ?: unit.str("name") ?: return@mapNotNull null
            id to name
        }.toMap()
    }
}
