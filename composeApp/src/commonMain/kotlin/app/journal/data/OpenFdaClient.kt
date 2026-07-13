package app.journal.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenFDA drug interaction data — fetched from the FDA drug label endpoint.
 *
 * Covers substances that are FDA-approved drugs (ketamine, diazepam,
 * amphetamine, modafinil, etc.). Supplements the SMW/TripSit interaction data.
 *
 * API docs: https://open.fda.gov/apis/drug/label/
 */
object OpenFdaClient {

    private const val BASE = "https://api.fda.gov/drug/label.json"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Simplified drug interaction info from an FDA label.
     */
    data class FdaInteractionResult(
        val brandName: String? = null,
        val genericName: String? = null,
        /** Full text of the DRUG INTERACTIONS section. */
        val drugInteractions: List<String> = emptyList(),
        /** Full text of the CONTRAINDICATIONS section. */
        val contraindications: List<String> = emptyList(),
        /** Full text of WARNINGS AND PRECAUTIONS. */
        val warnings: List<String> = emptyList()
    ) {
        val hasData: Boolean get() = drugInteractions.isNotEmpty()
    }

    /**
     * Fetch interaction data for a substance by its generic name.
     * Uses Ktor client — works on all platforms.
     */
    suspend fun fetchInteractions(
        genericName: String,
        client: HttpClient
    ): Result<FdaInteractionResult> {
        return try {
            val searchTerm = genericName.lowercase().replace(" ", "+")
            val response = client.get("$BASE?search=openfda.generic_name:$searchTerm&limit=1")
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonArray ?: return Result.success(FdaInteractionResult())

            if (results.isEmpty()) return Result.success(FdaInteractionResult())

            val label = results[0].jsonObject

            val openfda = label["openfda"]?.jsonObject
            val brandName = openfda?.get("brand_name")?.jsonArray?.get(0)?.jsonPrimitive?.content
            val genericNameResult = openfda?.get("generic_name")?.jsonArray?.get(0)?.jsonPrimitive?.content

            fun extractList(key: String): List<String> =
                label[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

            Result.success(FdaInteractionResult(
                brandName = brandName,
                genericName = genericNameResult,
                drugInteractions = extractList("drug_interactions"),
                contraindications = extractList("contraindications"),
                warnings = extractList("warnings_and_cautions")
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
