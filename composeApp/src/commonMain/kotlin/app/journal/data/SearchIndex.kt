package app.journal.data

import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A search result pointing to an entity in the journal.
 */
data class SearchResult(
    val entityType: String,
    val entityId: String,
    val title: String,
    val snippet: String,
    val score: Int
)

/**
 * Lightweight full-text search index for the journal.
 *
 * Builds a simple inverted index from all text fields of all entity types.
 * The dataset is small enough that rebuilding from scratch on every mutation
 * is fast (< 10ms for a typical journal). The index is held in-memory and
 * rebuilt on load and after bulk mutations.
 */
class SearchIndex {

    // Inverted index: lowercase word -> SearchResult entries containing that word
    private val index = mutableMapOf<String, MutableSet<SearchResult>>()

    // Entity ID -> words it contributed (for clean rebuild)
    private var isBuilt = false

    val isEmpty: Boolean get() = !isBuilt

    /**
     * Rebuild the entire search index from the repository's current state.
     * Call after loading data or after batch mutations.
     */
    fun rebuild(repo: JournalRepository) = synchronized(this) {
        index.clear()
        isBuilt = false

        for (session in repo.sessions.value) {
            val dt = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            indexEntity(
                "session", session.id,
                title = session.title,
                score = 10,
                texts = listOfNotNull(
                    session.title,
                    session.set,
                    session.setting,
                    session.intention,
                    session.outcome,
                    session.shulginRating,
                    dt.date.toString()
                )
            )
        }

        for (sub in repo.substances.value) {
            indexEntity(
                "substance", sub.id,
                title = sub.name,
                score = 8,
                texts = listOfNotNull(
                    sub.name,
                    sub.summary,
                    sub.chemicalProperties?.iupacName,
                    sub.chemicalProperties?.molecularFormula,
                    sub.cid?.toString()
                ) + sub.aliases
            )
        }

        for (note in repo.notes.value) {
            indexEntity(
                "note", note.id,
                title = note.title ?: "Untitled note",
                score = 5,
                texts = listOfNotNull(note.title, note.body)
            )
        }

        for (dose in repo.doses.value) {
            val subName = repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId
            indexEntity(
                "dose", dose.id,
                title = "$subName (${dose.amount} ${dose.unit})",
                score = 3,
                texts = listOfNotNull(
                    subName,
                    dose.routeOfAdministration,
                    dose.notes,
                    dose.amount.toString(),
                    dose.unit
                )
            )
        }

        for (event in repo.timelineEvents.value) {
            indexEntity(
                "event", event.id,
                title = event.label ?: "Timeline event",
                score = 3,
                texts = listOfNotNull(event.label, event.body)
            )
        }

        for (effect in repo.effects.value) {
            indexEntity(
                "effect", effect.id,
                title = effect.name,
                score = 4,
                texts = listOfNotNull(effect.name, effect.description)
            )
        }

        isBuilt = true
    }

    /**
     * Search the index for entities matching the query.
     * Returns results sorted by relevance (multi-term matches first, then single term).
     */
    fun search(query: String): List<SearchResult> = synchronized(this) {
        if (!isBuilt || query.isBlank()) return emptyList()

        val terms = query.lowercase()
            .split(Regex("[\\s,;:.!?()\\[\\]{}<>/\\\\@#\$%^&*+=|~`\"'\\u2013\\u2014]+"))
            .filter { it.length >= 2 }

        if (terms.isEmpty()) return emptyList()

        // Single term: return all matches containing that substring, ranked by score
        if (terms.size == 1) {
            val term = terms[0]
            val results = index.entries
                .filter { it.key.contains(term) }
                .flatMap { it.value }
            return sortAndDedupe(results)
        }

        // Multiple terms: rank by how many terms hit each entity
        val entityScores = mutableMapOf<String, MutableList<SearchResult>>()
        for ((word, results) in index) {
            val matchingTerms = terms.filter { word.contains(it) }
            if (matchingTerms.isEmpty()) continue
            for (result in results) {
                entityScores.getOrPut(result.entityId) { mutableListOf() }.add(result)
            }
        }

        return entityScores.entries
            .sortedByDescending { (_, results) ->
                // Rank: count of distinct matching terms + max entity score
                val distinctTerms = results.map { r ->
                    terms.count { t ->
                        index.entries.any { (w, rs) ->
                            w.contains(t) && rs.any { it.entityId == r.entityId }
                        }
                    }
                }.maxOrNull() ?: 0
                distinctTerms * 100 + (results.maxOfOrNull { it.score } ?: 0)
            }
            .take(50)
            .mapNotNull { (_, results) ->
                results.maxByOrNull { it.score }
            }
    }

    /**
     * Get the session ID from a search result if the result type is "session".
     */
    fun sessionId(result: SearchResult): String? =
        if (result.entityType == "session") result.entityId else null

    // ---- Private helpers ----

    private fun indexEntity(
        type: String,
        id: String,
        title: String,
        score: Int = 5,
        texts: List<String?>
    ) {
        val fullText = texts.filterNotNull().joinToString(" ").lowercase()
        val words = fullText.split(Regex("[\\s,;:.!?()\\[\\]{}<>/\\\\@#\$%^&*+=|~`\"'\\u2013\\u2014]+"))
            .filter { it.length >= 2 }
            .distinct()

        val snippet = texts.drop(1).firstNotNullOfOrNull { it?.take(160) } ?: ""
        val result = SearchResult(
            entityType = type,
            entityId = id,
            title = title.take(100),
            snippet = snippet,
            score = score
        )

        for (word in words) {
            index.getOrPut(word) { mutableSetOf() }.add(result)
        }
    }

    private fun sortAndDedupe(results: Iterable<SearchResult>): List<SearchResult> =
        results
            .groupBy { it.entityId }
            .map { (_, rs) -> rs.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }
            .take(50)
}
