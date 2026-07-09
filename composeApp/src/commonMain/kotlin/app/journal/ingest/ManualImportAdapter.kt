package app.journal.ingest

import app.journal.model.Substance
import kotlinx.serialization.json.*

/**
 * Manual import fallback for sources without public APIs:
 *   - Dose.wiki   — no confirmed structured API
 *   - Erowid      — primarily HTML; no public REST/GraphQL API
 *   - Effect Index — effectindex.com; no confirmed public API
 *
 * Expected format: JSON array of objects with at minimum { "name": "..." }
 * Optional: "dosageBands", "durationProfile", "summary" (same shape as Substance fields)
 */
class ManualImportAdapter(private val repository: IngestRepository) {
    suspend fun importFromJson(raw: String, sourceName: String): IngestReport {
        var upserted = 0
        val errors = mutableListOf<String>()
        return try {
            val nowMs = currentTimeMs()
            val arr = Json.parseToJsonElement(raw) as? JsonArray
                ?: return IngestReport(0, 0, 0, listOf("Root must be JSON array"))
            for (elem in arr) {
                try {
                    val obj = elem as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val id = "$sourceName:${name.lowercase().replace(" ", "_")}"
                    repository.upsertSubstance(Substance(
                        id = id, name = name,
                        sourceVersion = sourceName, cachedAt = nowMs,
                        createdAt = nowMs, updatedAt = nowMs
                    ))
                    upserted++
                } catch (e: Exception) { errors.add("Parse: ${e.message}") }
            }
            IngestReport(upserted, 0, 0, errors)
        } catch (e: Exception) {
            IngestReport(0, 0, 0, listOf("Import failed: ${e.message}"))
        }
    }
}

data class IngestReport(
    val substancesUpserted: Int,
    val effectsUpserted: Int,
    val interactionsUpserted: Int,
    val errors: List<String>
)
