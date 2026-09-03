package app.journal.ingest

import app.journal.model.Substance
import kotlinx.serialization.json.*

/**
 * Manual import fallback for sources without public APIs:
 *   - Dose.wiki   : no confirmed structured API
 *   - Erowid      : primarily HTML; no public REST/GraphQL API
 *   - Effect Index : effectindex.com; no confirmed public API
 *
 * Expected format: JSON array of objects with at minimum { "name": "..." }
 * Optional: "dosageBands", "durationProfile", "summary" (same shape as Substance fields)
 */
class ManualImportAdapter(private val repository: IngestRepository) {
    suspend fun importFromJson(raw: String, sourceName: String): IngestReport {
        var upserted = 0
        val errors = mutableListOf<String>()
        return try {
            // Input cap before parsing: refuse payloads over 50 MB.
            if (raw.length > MAX_MANUAL_IMPORT_BYTES) {
                return IngestReport(0, 0, 0, listOf("Import too large (max 50 MB)"))
            }
            if (sourceName.isBlank() || sourceName.length > MAX_SOURCE_NAME_LEN) {
                return IngestReport(0, 0, 0, listOf("Invalid source name"))
            }
            val nowMs = currentTimeMs()
            val minTs = 946684800000L // 2000-01-01T00:00:00Z
            val maxTs = nowMs + 86400000L // now plus 1 day
            val arr = Json.parseToJsonElement(raw) as? JsonArray
                ?: return IngestReport(0, 0, 0, listOf("Root must be JSON array"))
            if (arr.size > MAX_MANUAL_ENTRIES) {
                return IngestReport(0, 0, 0, listOf("Too many entries (max $MAX_MANUAL_ENTRIES)"))
            }
            for (elem in arr) {
                try {
                    val obj = elem as? JsonObject ?: continue
                    val nameRaw = obj["name"]?.jsonPrimitive?.content ?: continue
                    val name = nameRaw.trim().take(MAX_SUBSTANCE_NAME_LEN)
                    if (name.isBlank()) continue
                    if (nowMs !in minTs..maxTs) {
                        errors.add("Import clock out of range; refusing entry '$name'")
                        continue
                    }
                    val id = "$sourceName:${name.lowercase().replace(" ", "_")}"
                    if (id.length > MAX_SUBSTANCE_ID_LEN) {
                        errors.add("Skipping oversized id for '$name'")
                        continue
                    }
                    repository.upsertSubstance(Substance(
                        id = id, name = name,
                        sourceVersion = sourceName.take(MAX_SOURCE_NAME_LEN), cachedAt = nowMs,
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

    companion object {
        /** Refuse manual-import payloads larger than 50 MB before parsing. */
        const val MAX_MANUAL_IMPORT_BYTES = 50L * 1024 * 1024
        /** Cap on entries per manual-import file. */
        const val MAX_MANUAL_ENTRIES = 10000
        const val MAX_SUBSTANCE_NAME_LEN = 200
        const val MAX_SUBSTANCE_ID_LEN = 256
        const val MAX_SOURCE_NAME_LEN = 100
    }
}

data class IngestReport(
    val substancesUpserted: Int,
    val effectsUpserted: Int,
    val interactionsUpserted: Int,
    val errors: List<String>
)
