package app.journal.data

import androidx.compose.runtime.Immutable
import app.journal.model.*
import app.journal.util.PlatformLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A search result pointing to an entity in the journal.
 */
@Immutable
data class SearchResult(
    val entityType: String,
    val entityId: String,
    val title: String,
    val snippet: String,
    val score: Int
)

/**
 * Token scanner shared by index building and query parsing, so the two paths
 * can never drift apart (they used to be two copies of one regex).
 *
 * A token is a maximal run of characters outside the separator set: ASCII
 * whitespace plus `,;:.!?()[]{}<>/\@#$%^&*+=|~`"'` and the en/em dashes.
 * Separator runs collapse, and no empty token is ever produced, which is the
 * shape `Regex.split(...).filter { it.length >= 2 }` had. The regex split was
 * the single largest cost of a rebuild (102 ms of 144 ms on a 4000-session
 * journal): per-token Matcher bookkeeping plus an intermediate list per
 * entity. A char loop allocates only the token strings themselves.
 */
internal object SearchTokenizer {

    private val ASCII_SEPARATORS = BooleanArray(128).also { table ->
        for (c in " \t\n\u000B\u000C\r,;:.!?()[]{}<>/\\@#\$%^&*+=|~`\"'") {
            table[c.code] = true
        }
    }

    private fun isSeparator(c: Char): Boolean =
        if (c.code < 128) ASCII_SEPARATORS[c.code] else c == '\u2013' || c == '\u2014'

    /** Calls [sink] once per token of [text], in order, without trimming or lowercasing. */
    fun forEachToken(text: CharSequence, sink: (String) -> Unit) {
        var start = -1
        for (i in text.indices) {
            if (isSeparator(text[i])) {
                if (start >= 0) {
                    sink(text.substring(start, i))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) sink(text.substring(start))
    }

    /** Tokens of [text] at least [minLength] characters long, in order. */
    fun tokens(text: CharSequence, minLength: Int = 2): List<String> {
        val out = ArrayList<String>()
        forEachToken(text) { if (it.length >= minLength) out.add(it) }
        return out
    }
}

/**
 * Lightweight full-text search index for the journal.
 *
 * Builds a simple inverted index from all text fields of all entity types.
 * The dataset is small enough that a full rebuild from scratch is fast
 * (tens of ms for a typical journal), so the index is never maintained
 * incrementally. JournalRepository marks it dirty on every mutation and
 * rebuilds lazily inside search(), so results are never stale; bulk applies
 * also mark it dirty.
 *
 * Locking and scoring (wave5): a rebuild builds a brand-new word map and
 * publishes it in one assignment, so [snapshotIndex] hands out the frozen
 * map itself instead of copying every bucket on every keystroke (the copy was
 * 3-4 ms per keystroke on a 4000-session journal). Scoring then runs on that
 * immutable snapshot with NO lock held, so a keystroke search can never block
 * (or be blocked by) a concurrent rebuild, and scoring itself is a single
 * linear pass (see [scoreSearchSnapshot]). A reader that took the previous
 * snapshot keeps a consistent, fully usable view: rebuilds never mutate a
 * published map.
 */
class SearchIndex {

    /** The frozen publication; replaced whole on each [rebuild], never mutated afterwards. */
    private var published: Map<String, List<SearchResult>> = emptyMap()
    private var isBuilt = false
    private val lock = PlatformLock()

    val isEmpty: Boolean get() = lock.withLock { !isBuilt }

    fun rebuild(
        sessions: List<Session>,
        substances: List<Substance>,
        notes: List<Note>,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>,
        effects: List<Effect>,
        substanceNames: Map<String, String>
    ) = lock.withLock {
        val index = LinkedHashMap<String, MutableList<SearchResult>>(512)
        val zone = TimeZone.currentSystemDefault()

        for (session in sessions) {
            val dt = Instant.fromEpochMilliseconds(session.startTime).toLocalDateTime(zone)
            indexEntity(
                index,
                "session", session.id,
                title = session.title,
                score = 10,
                texts = listOfNotNull(
                    session.title, session.set, session.setting,
                    session.intention, session.outcome, session.freeform,
                    session.shulginRating, dt.date.toString()
                )
            )
        }

        for (sub in substances) {
            indexEntity(
                index,
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
                index,
                "note", note.id,
                title = note.title ?: "Untitled note",
                score = 5,
                texts = listOfNotNull(note.title, note.body)
            )
        }

        for (dose in doses) {
            val subName = substanceNames[dose.substanceId] ?: dose.substanceId
            indexEntity(
                index,
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
                index,
                "event", event.id,
                title = event.label,
                score = 3,
                texts = listOfNotNull(event.label, event.body)
            )
        }

        for (effect in effects) {
            indexEntity(
                index,
                "effect", effect.id,
                title = effect.name,
                score = 4,
                texts = listOfNotNull(effect.name, effect.description)
            )
        }

        // Publish: readers see either the old frozen map or this new one.
        published = index
        isBuilt = true
    }

    /**
     * The current frozen word -> results map. No copy is taken: [rebuild]
     * replaces the publication instead of mutating it, so handing out the map
     * is safe and costs a lock and a field read.
     */
    internal fun snapshotIndex(): Map<String, List<SearchResult>> = lock.withLock {
        if (!isBuilt) emptyMap() else published
    }

    /**
     * Search the current index: snapshot under [lock], then score with no
     * lock held. See [scoreSearchSnapshot] for the ordering contract.
     */
    fun search(query: String): List<SearchResult> =
        scoreSearchSnapshot(query, snapshotIndex())

    fun sessionId(result: SearchResult): String? =
        if (result.entityType == "session") result.entityId else null


    private fun indexEntity(
        index: MutableMap<String, MutableList<SearchResult>>,
        type: String,
        id: String,
        title: String,
        score: Int = 5,
        texts: List<String?>,
    ) {
        var snippet = ""
        for (i in 1 until texts.size) {
            val text = texts[i]
            if (text != null) {
                snippet = text.take(160)
                break
            }
        }
        val result = SearchResult(
            entityType = type,
            entityId = id,
            title = title.take(100),
            snippet = snippet,
            score = score
        )

        // LinkedHashSet keeps first-occurrence word order, which sets the
        // snapshot's key order and with it the tie-break order of the scoring rule.
        val words = LinkedHashSet<String>()
        for (text in texts) {
            if (text.isNullOrEmpty()) continue
            val lower = text.lowercase()
            SearchTokenizer.forEachToken(lower) { word ->
                if (word.length >= 2) words.add(word)
            }
        }
        for (word in words) {
            index.getOrPut(word) { ArrayList(1) }.add(result)
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

    val terms = SearchTokenizer.tokens(query.lowercase())
    if (terms.isEmpty()) return emptyList()

    if (terms.size == 1) {
        val term = terms[0]
        val results = snapshot.entries
            .filter { it.key.contains(term) }
            .flatMap { it.value }
        return sortAndDedupe(results)
    }

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
