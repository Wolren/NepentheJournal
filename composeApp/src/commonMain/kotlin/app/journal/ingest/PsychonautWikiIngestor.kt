package app.journal.ingest

import app.journal.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

/**
 * PsychonautWiki GraphQL ingestor.
 * Endpoint: POST https://api.psychonautwiki.org/   (no API key required)
 *
 * Confirmed schema from api.psychonautwiki.org playground + mouseroot/psychwiki.py:
 *
 * substances(query: String) {
 *   name summary
 *   class { chemical psychoactive }
 *   roas {
 *     name
 *     dose { units threshold heavy common{min max} light{min max} strong{min max} }
 *     duration { onset comeup peak offset afterglow total }  // each: {min max units}
 *   }
 *   effects { name url }
 *   dangerousInteractions  { name }
 *   unsafeInteractions     { name }
 *   uncertainInteractions  { name }
 *   tolerance { full half zero }
 *   addictionPotential toxicity
 * }
 */
class PsychonautWikiIngestor(
    private val httpClient: HttpClient,
    private val repository: IngestRepository
) {
    private val endpoint = "https://api.psychonautwiki.org/"

    suspend fun fetchAndStore(query: String, nowMs: Long = currentTimeMs()) {
        val raw = fetch(query)
        raw.forEach { s ->
            val substance = normalize(s, nowMs)
            repository.upsertSubstance(substance)
            s.effects.forEach { e ->
                repository.upsertEffect(Effect(
                    id = "pwiki-effect:${e.name.lowercase().replace(" ", "_")}",
                    name = e.name, url = e.url,
                    createdAt = nowMs, updatedAt = nowMs
                ))
            }
            fun interactionId(a: String, b: String): String {
                val sorted = listOf(a, b).sorted()
                return "interaction:${sorted[0]}:${sorted[1]}"
            }
            val substanceId = substance.id
            s.dangerousInteractions.forEach { r ->
                repository.upsertInteraction(Interaction(
                    id = interactionId(substanceId, "pwiki:${r.name.lowercase().replace(" ", "_")}"),
                    substanceAId = substanceId,
                    substanceBId = "pwiki:${r.name.lowercase().replace(" ", "_")}",
                    riskLevel = InteractionRisk.DANGEROUS,
                    sources = listOf("psychonautwiki"),
                    createdAt = nowMs, updatedAt = nowMs
                ))
            }
            s.unsafeInteractions.forEach { r ->
                repository.upsertInteraction(Interaction(
                    id = interactionId(substanceId, "pwiki:${r.name.lowercase().replace(" ", "_")}"),
                    substanceAId = substanceId,
                    substanceBId = "pwiki:${r.name.lowercase().replace(" ", "_")}",
                    riskLevel = InteractionRisk.UNSAFE,
                    sources = listOf("psychonautwiki"),
                    createdAt = nowMs, updatedAt = nowMs
                ))
            }
        }
    }

    private suspend fun fetch(query: String): List<PwikiSubstanceRaw> {
        val gql = buildGql(query)
        val response: PwikiGraphQLResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("query" to gql))
        }.body()
        return response.data?.substances ?: emptyList()
    }

    fun normalize(raw: PwikiSubstanceRaw, nowMs: Long): Substance {
        val id = "pwiki:${raw.name.lowercase().replace(" ", "_")}"
        val firstRoa = raw.roas.firstOrNull()
        val dosageBands = firstRoa?.dose?.let { d ->
            buildMap {
                d.threshold?.let { put("threshold", "$it ${d.units ?: ""}".trim()) }
                d.light?.let    { put("light",     "${it.min}–${it.max} ${d.units ?: ""}".trim()) }
                d.common?.let   { put("common",    "${it.min}–${it.max} ${d.units ?: ""}".trim()) }
                d.strong?.let   { put("strong",    "${it.min}–${it.max} ${d.units ?: ""}".trim()) }
                d.heavy?.let    { put("heavy",     "$it ${d.units ?: ""}".trim()) }
            }
        } ?: emptyMap()
        val durationProfile = firstRoa?.duration?.let { dur ->
            buildMap {
                dur.onset?.let     { put("onset",     "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
                dur.comeup?.let    { put("comeup",    "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
                dur.peak?.let      { put("peak",      "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
                dur.offset?.let    { put("offset",    "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
                dur.afterglow?.let { put("afterglow", "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
                dur.total?.let     { put("total",     "${it.min}–${it.max} ${it.units ?: ""}".trim()) }
            }
        } ?: emptyMap()
        return Substance(
            id = id, pwikiId = raw.name, name = raw.name,
            summary = raw.summary,
            substanceClass = listOfNotNull(
                raw.`class`?.chemical?.joinToString(", "),
                raw.`class`?.psychoactive?.joinToString(", ")
            ).filter { it.isNotBlank() },
            routesOfAdministration = raw.roas.map { it.name },
            dosageBands = dosageBands,
            durationProfile = durationProfile,
            addictionPotential = raw.addictionPotential,
            toxicity = raw.toxicity ?: emptyList(),
            cachedAt = nowMs, sourceVersion = "pwiki-v1",
            createdAt = nowMs, updatedAt = nowMs
        )
    }

    private fun buildGql(query: String): String {
        val escaped = query.replace("\"\"\"", "\\\"")
        return """{
          substances(query: "$escaped") {
            name summary
            class { chemical psychoactive }
            roas {
              name
              dose { units threshold heavy common{min max} light{min max} strong{min max} }
              duration {
                onset{min max units} comeup{min max units} peak{min max units}
                offset{min max units} afterglow{min max units} total{min max units}
              }
            }
            effects { name url }
            dangerousInteractions  { name }
            unsafeInteractions     { name }
            uncertainInteractions  { name }
            addictionPotential toxicity
          }
        }""".trimIndent()
    }
}

// ===== Raw response models =====
@Serializable data class PwikiGraphQLResponse(val data: PwikiData? = null)
@Serializable data class PwikiData(val substances: List<PwikiSubstanceRaw> = emptyList())
@Serializable data class PwikiSubstanceRaw(
    val name: String,
    val summary: String? = null,
    val `class`: PwikiClass? = null,
    val roas: List<PwikiRoa> = emptyList(),
    val effects: List<PwikiEffect> = emptyList(),
    val dangerousInteractions: List<PwikiRef> = emptyList(),
    val unsafeInteractions: List<PwikiRef> = emptyList(),
    val uncertainInteractions: List<PwikiRef> = emptyList(),
    val addictionPotential: String? = null,
    val toxicity: List<String>? = null
)
@Serializable data class PwikiClass(val chemical: List<String>? = null, val psychoactive: List<String>? = null)
@Serializable data class PwikiRoa(val name: String, val dose: PwikiDose? = null, val duration: PwikiDuration? = null)
@Serializable data class PwikiDose(
    val units: String? = null, val threshold: Double? = null, val heavy: Double? = null,
    val light: PwikiRange? = null, val common: PwikiRange? = null, val strong: PwikiRange? = null
)
@Serializable data class PwikiRange(val min: Double? = null, val max: Double? = null)
@Serializable data class PwikiDuration(
    val onset: PwikiRangeUnit? = null, val comeup: PwikiRangeUnit? = null,
    val peak: PwikiRangeUnit? = null, val offset: PwikiRangeUnit? = null,
    val afterglow: PwikiRangeUnit? = null, val total: PwikiRangeUnit? = null
)
@Serializable data class PwikiRangeUnit(val min: Double? = null, val max: Double? = null, val units: String? = null)
@Serializable data class PwikiEffect(val name: String, val url: String? = null)
@Serializable data class PwikiRef(val name: String)

interface IngestRepository {
    suspend fun upsertSubstance(substance: app.journal.model.Substance)
    suspend fun upsertEffect(effect: app.journal.model.Effect)
    suspend fun upsertInteraction(interaction: app.journal.model.Interaction)
}

expect fun currentTimeMs(): Long
