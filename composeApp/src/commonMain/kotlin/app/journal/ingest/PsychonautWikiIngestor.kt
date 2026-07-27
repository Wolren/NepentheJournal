package app.journal.ingest

import app.journal.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

class PsychonautWikiIngestor(
    private val httpClient: HttpClient,
    private val repository: IngestRepository
) {
    private val endpoint = "https://api.psychonautwiki.org/"

    private fun interactionId(a: String, b: String): String {
        val sorted = listOf(a, b).sorted()
        return "interaction:${sorted[0]}:${sorted[1]}"
    }

    suspend fun fetchAndStore(query: String, nowMs: Long = currentTimeMs()) {
        val raw = fetch(query)
        raw.forEach { s ->
            val substance = normalize(s, nowMs)
            repository.upsertSubstance(substance)
            val substanceId = substance.id
            storeInteractions(substanceId, s.dangerousInteractions, InteractionRisk.DANGEROUS, nowMs)
            storeInteractions(substanceId, s.unsafeInteractions, InteractionRisk.UNSAFE, nowMs)
            storeInteractions(substanceId, s.uncertainInteractions, InteractionRisk.UNCERTAIN, nowMs)
        }
    }

    private fun storeInteractions(
        substanceId: String,
        refs: List<PwikiRef>,
        riskLevel: InteractionRisk,
        nowMs: Long
    ) {
        for (r in refs) {
            val targetId = "pwiki:${r.name.lowercase().replace(" ", "_")}"
            repository.upsertInteraction(Interaction(
                id = interactionId(substanceId, targetId),
                substanceAId = substanceId,
                substanceBId = targetId,
                riskLevel = riskLevel,
                sources = listOf("psychonautwiki"),
                createdAt = nowMs, updatedAt = nowMs
            ))
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
                raw.`class`?.chemical,
                raw.`class`?.psychoactive
            ).flatten().let { SubstanceClassNormalizer.normalize(it) },
            routesOfAdministration = raw.roas.map { it.name },
            dosageBands = dosageBands,
            durationProfile = durationProfile,
            addictionPotential = raw.addictionPotential,
            toxicity = raw.toxicity ?: emptyList(),
            effects = raw.effects.map { it.name },
            cachedAt = nowMs, sourceVersion = "pwiki-v1",
            createdAt = nowMs, updatedAt = nowMs
        )
    }

    private fun buildGql(query: String): String {
        val escaped = query.replace("\"\"\"", "\\\"")
        return """{ substances(query: "$escaped") { name summary class { chemical psychoactive } roas { name dose { units threshold heavy common{min max} light{min max} strong{min max} } duration { onset{min max units} comeup{min max units} peak{min max units} offset{min max units} afterglow{min max units} total{min max units} } } effects { name url } dangerousInteractions { name } unsafeInteractions { name } uncertainInteractions { name } addictionPotential toxicity } }""".trimIndent()
    }
}

@Serializable data class PwikiGraphQLResponse(val data: PwikiData? = null)
@Serializable data class PwikiData(val substances: List<PwikiSubstanceRaw> = emptyList())
@Serializable data class PwikiSubstanceRaw(
    val name: String, val summary: String? = null,
    val `class`: PwikiClass? = null, val roas: List<PwikiRoa> = emptyList(),
    val effects: List<PwikiEffect> = emptyList(),
    val dangerousInteractions: List<PwikiRef> = emptyList(),
    val unsafeInteractions: List<PwikiRef> = emptyList(),
    val uncertainInteractions: List<PwikiRef> = emptyList(),
    val addictionPotential: String? = null, val toxicity: List<String>? = null
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


expect fun currentTimeMs(): Long
