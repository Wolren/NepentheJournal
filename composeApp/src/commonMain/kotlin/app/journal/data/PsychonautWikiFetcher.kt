package app.journal.data

import app.journal.data.AppJson
import app.journal.ingest.*
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.util.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class PsychonautWikiFetcher(
    private val repo: IngestRepository,
    private val endpoint: String = ENDPOINT
) {
    private val json = AppJson.json
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }

    suspend fun fetchAll(
        onProgress: (fetched: Int, chunksDone: Int, chunksTotal: Int) -> Unit = { _, _, _ -> }
    ): Int {
        withContext(Dispatchers.Default) {
            val ingestor = PsychonautWikiIngestor(httpClient = client, repository = repo)
            try {
                walkAll(resolver = { queryWithRetry(it) }, ingestor = ingestor, onProgress = onProgress)
            } finally { client.close() }
        }
        return 0
    }

    suspend fun walkAll(
        resolver: suspend (prefix: String) -> List<PwikiSubstanceRaw>?,
        ingestor: PsychonautWikiIngestor,
        onProgress: (fetched: Int, chunksDone: Int, chunksTotal: Int) -> Unit = { _, _, _ -> },
        gapDelayMs: Long = REQUEST_GAP_MS
    ): Int {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>(); queue.addLast("")
        var chunksDone = 0; var chunksTotal = 1; var rateLimitStreak = 0; var totalChunks = 0
        val MAX_CHUNKS = 5000
        try {
            while (queue.isNotEmpty()) {
                if (totalChunks >= MAX_CHUNKS) break
                if (rateLimitStreak >= 5) break
                val prefix = queue.removeFirst()
                chunksTotal = queue.size + chunksDone + 1; totalChunks++
                val raw = try { resolver(prefix) } catch (e: Exception) { rateLimitStreak++; delay(2000); continue }
                    ?: run { rateLimitStreak++; if (rateLimitStreak < 5) queue.addFirst(prefix); delay(1500); continue }
                rateLimitStreak = 0
                if (raw.isEmpty()) { chunksDone++; onProgress(seen.size, chunksDone, chunksTotal); continue }
                if (raw.size < 10) { for (s in raw) ingestOne(ingestor, s, seen); chunksDone++ }
                else { for (c in PREFIX_CHARS) queue.addLast(prefix + c) }
                onProgress(seen.size, chunksDone, chunksTotal)
                if (gapDelayMs > 0) delay(gapDelayMs)
            }
        } catch (_: Exception) {}
        return if (seen.isEmpty()) -1 else seen.size
    }

    private suspend fun ingestOne(ingestor: PsychonautWikiIngestor, raw: PwikiSubstanceRaw, seen: LinkedHashSet<String>) {
        val substance = ingestor.normalize(raw, currentTimeMillis())
        if (seen.add(substance.id)) {
            repo.upsertSubstance(substance)
            fun interactionId(a: String, b: String) = "interaction:${listOf(a, b).sorted()[0]}:${listOf(a, b).sorted()[1]}"
            val sid = substance.id
            raw.dangerousInteractions.forEach { r ->
                repo.upsertInteraction(Interaction(id = interactionId(sid, "pwiki:${r.name.lowercase().replace(" ", "_")}"),
                    substanceAId = sid, substanceBId = "pwiki:${r.name.lowercase().replace(" ", "_")}",
                    riskLevel = InteractionRisk.DANGEROUS, sources = listOf("psychonautwiki"),
                    createdAt = substance.createdAt, updatedAt = substance.updatedAt))
            }
            raw.unsafeInteractions.forEach { r ->
                repo.upsertInteraction(Interaction(id = interactionId(sid, "pwiki:${r.name.lowercase().replace(" ", "_")}"),
                    substanceAId = sid, substanceBId = "pwiki:${r.name.lowercase().replace(" ", "_")}",
                    riskLevel = InteractionRisk.UNSAFE, sources = listOf("psychonautwiki"),
                    createdAt = substance.createdAt, updatedAt = substance.updatedAt))
            }
        }
    }

    private suspend fun queryWithRetry(prefix: String): List<PwikiSubstanceRaw>? {
        var lastErr: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = client.post(endpoint) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("query" to buildQuery(prefix)))
                }.body<PwikiGraphQLResponse>()
                val list = response.data?.substances
                if (list != null) return list
            } catch (e: Exception) { lastErr = e }
            delay(RETRY_BASE_MS * (attempt + 1))
        }
        if (lastErr != null) throw lastErr!!
        return null
    }

    private fun buildQuery(prefix: String): String {
        val q = if (prefix.isEmpty()) "" else "(query: \"$prefix\")"
        return """{ substances$q { name summary class { chemical psychoactive } roas { name dose { units threshold heavy common{min max} light{min max} strong{min max} } duration { onset{min max units} comeup{min max units} peak{min max units} offset{min max units} afterglow{min max units} total{min max units} } } effects { name url } dangerousInteractions { name } unsafeInteractions { name } uncertainInteractions { name } addictionPotential toxicity } }""".trimIndent()
    }

    companion object {
        private const val ENDPOINT = "https://api.psychonautwiki.org/"
        private const val USER_AGENT = "NepentheJournal/0.1 (+https://github.com/Wolren/psychonaut-journal)"
        private const val MAX_RETRIES = 4
        private const val RETRY_BASE_MS = 800L
        private const val REQUEST_GAP_MS = 350L
        private val PREFIX_CHARS = ('a'..'z') + ('0'..'9')
    }
}
