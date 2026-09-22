package app.journal.data

import app.journal.model.*
import app.journal.util.PlatformLock
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
 * Token separator shared by index building and query parsing: any run of
 * whitespace or punctuation splits a token, en-dashes included. Hoisted so
 * the two paths can never drift apart (they were two copies of the same
 * inline regex).
 */
private val TOKEN_SEPARATOR =
    Regex("[\\s,;:.!?()\\[\\]{}<>/\\\\@#\\$%^&*+=|~`\\\"'\\u2013\\u2014]+")

/**
 * Lightweight full-text search index for the journal.
 *
 * Builds a simple inverted index from all text fields of all entity types.
 * The dataset is small enough that a full rebuild from scratch is fast
 * (< 10ms for a typical journal), so the index is never maintained
 * incrementally. JournalRepository marks it dirty on every mutation and
 * rebuilds lazily inside search() (results are never stale) plus once per
 * auto-save quiet period; bulk applies rebuild eagerly.
 *
 * Locking and scoring (wave4): [search] only holds [lock] long enough to
 * copy the inverted index out ([snapshotIndex]); the scoring pass runs on
 * that immutable snapshot with NO lock held, so a keystroke search can
 * never block (or be blocked by) a concurrent rebuild, and scoring itself
 * is a single linear pass (see [scoreSearchSnapshot]).
 */
class SearchIndex {

    private val index = mutableMapOf<String, MutableSet<SearchResult>>()
    private var isBuilt = false
    private val lock = PlatformLock()

    val isEmpty: Boolean get() = !isBuilt

    fun rebuild(
        sessions: List<Session>,
        substances: List<Substance>,
        notes: List<Note>,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>,
        effects: List<Effect>,
        substanceNames: Map<String, String>
    ) = lock.withLock {
        index.clear()
        isBuilt = false

        for (session in sessions) {
            val dt = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            indexEntity(
                "session", session.id,
                title = session.title,
                score = 10,
                texts = listOfNotNull(
                    session.title, session.set, session.setting,
                    session.intention, session.outcome,
                    session.shulginRating, dt.date.toString()
                )
            )
        }

        for (sub in substances) {
            indexEntity(
                "substance", sub.id,
                title = sub.name,
                score = 8,
                texts = listOfNotNull(
                    sub.name, sub.summary,
                    sub.chemicalProperties?.iupacName,
                    sub.chemicalProperties?.molecularFormula,
                    sub.cid?.toString()
                ) + sub.aliases
            )
        }

        for (note in notes) {
            indexEntity(
                "note", note.id,
                title = note.title ?: "Untitled note",
                score = 5,
                texts = listOfNotNull(note.title, note.body)
            )
        }

        for (dose in doses) {
            val subName = substanceNames[dose.substanceId] ?: dose.substanceId
            indexEntity(
                "dose", dose.id,
                title = "$subName (${dose.amount} ${dose.unit})",
                score = 3,
                texts = listOfNotNull(
                    subName, dose.routeOfAdministration,
                    dose.notes, dose.amount.toString(), dose.unit
                )
            )
        }

        for (event in timelineEvents) {
            indexEntity(
                "event", event.id,
                title = event.label,
                score = 3,
                texts = listOfNotNull(event.label, event.body)
            )
        }

        for (effect in effects) {
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
     * Point-in-time copy of the inverted index (word -> results), preserving
     * the exact iteration order of the live map. Callers score against this
     * snapshot instead of the live index, so scoring needs no lock and can
     * never race a concurrent [rebuild] (the hazard the old in-lock,
     * live-index scoring had).
     */
    internal fun snapshotIndex(): Map<String, List<SearchResult>> = lock.withLock {
        if (!isBuilt) return@withLock emptyMap()
        val snapshot = LinkedHashMap<String, List<SearchResult>>(index.size)
        for ((word, results) in index) snapshot[word] = results.toList()
        snapshot
    }

    /**
     * Search the current index: snapshot under [lock], then score with no
     * lock held. See [scoreSearchSnapshot] for the ordering contract.
     */
    fun search(query: String): List<SearchResult> =
        scoreSearchSnapshot(query, snapshotIndex())

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
        val words = fullText.split(TOKEN_SEPARATOR)
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
}

/**
 * Score [query] against an immutable index [snapshot]. Pure: touches no
 * shared state, needs no lock, and is safe to call with a snapshot taken
 * before (or during) a concurrent rebuild.
 *
 * Complexity: ONE pass over the snapshot words plus one pass over the
 * buckets, O(total index entries + terms), replacing the old
 * O(entities x results x terms x words) nested `index.entries.any` scan
 * that ran inside `sortedByDescending` while the repository lock was held.
 *
 * Ordering contract (byte-for-byte the pre-refactor rule, preserved):
 * - Single term: bucket results in snapshot word order, keep the
 *   highest-score result per entity (first max wins), stable
 *   `sortedByDescending { score }`, take 50.
 * - Multi term: entity buckets are created in first-encounter order while
 *   scanning the snapshot's words (word insertion order from rebuild);
 *   key = `distinctMatchedTerms * 100 + max entity score` where
 *   distinctMatchedTerms counts query terms that hit ANY word of that
 *   entity; stable `sortedByDescending` means TIES KEEP BUCKET INSERTION
 *   ORDER (first-seen entity first), take 50, then the bucket's
 *   highest-score result (first max wins).
 * The stable sort plus bucket-creation order is the tie-break the old
 * code had; single-pass scoring reproduces it exactly.
 */
internal fun scoreSearchSnapshot(
    query: String,
    snapshot: Map<String, List<SearchResult>>
): List<SearchResult> {
    if (snapshot.isEmpty() || query.isBlank()) return emptyList()

    val terms = query.lowercase()
        .split(TOKEN_SEPARATOR)
        .filter { it.length >= 2 }
    if (terms.isEmpty()) return emptyList()

    if (terms.size == 1) {
        val term = terms[0]
        val results = snapshot.entries
            .filter { it.key.contains(term) }
            .flatMap { it.value }
        return sortAndDedupe(results)
    }

    // Single pass: for every snapshot word that contains at least one query
    // term, bucket its results and record WHICH terms hit this entity. The old
    // code re-scanned the whole index for every (result x term) pair inside the
    // sort comparator to compute that same per-entity term count.
    val entityBuckets = LinkedHashMap<String, MutableList<SearchResult>>()
    val matchedTermsByEntity = HashMap<String, MutableSet<Int>>()
    for ((word, results) in snapshot) {
        if (results.isEmpty()) continue
        val hits = terms.indices.filter { word.contains(terms[it]) }
        if (hits.isEmpty()) continue
        for (result in results) {
            entityBuckets.getOrPut(result.entityId) { mutableListOf() }.add(result)
            matchedTermsByEntity.getOrPut(result.entityId) { HashSet() }.addAll(hits)
        }
    }

    return entityBuckets.entries
        .sortedByDescending { (entityId, results) ->
            val distinctTerms = matchedTermsByEntity[entityId]?.size ?: 0
            distinctTerms * 100 + (results.maxOfOrNull { it.score } ?: 0)
        }
        .take(50)
        .mapNotNull { (_, results) -> results.maxByOrNull { it.score } }
}

/** Group by entity keeping the highest score, stable sort by score, cap at 50. */
private fun sortAndDedupe(results: Iterable<SearchResult>): List<SearchResult> =
    results
        .groupBy { it.entityId }
        .mapNotNull { (_, rs) -> rs.maxByOrNull { it.score } }
        .sortedByDescending { it.score }
        .take(50)
