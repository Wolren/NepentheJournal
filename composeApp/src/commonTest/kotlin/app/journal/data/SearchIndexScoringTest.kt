package app.journal.data

import app.journal.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave4 search-scoring refactor pinning (task 1): the single-pass scorer in
 * SearchIndex.kt must reproduce the OLD rule exactly.
 *
 * The reference below is a verbatim copy of the pre-refactor algorithm
 * (including its O(entities x results x terms x words) `index.entries.any`
 * scan inside `sortedByDescending`), run over the same index snapshot. Any
 * divergence in ranking, tie-breaking, caps or result identity fails here.
 */
class SearchIndexScoringTest {

    // ---- Mixed fixture: sessions, substances, notes, doses, events, effects ----

    private fun session(id: String, title: String, set: String, setting: String, outcome: String) = Session(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        title = title,
        startTime = 1720728000000L,
        endTime = 1720742400000L,
        set = set,
        setting = setting,
        intention = "explore",
        outcome = outcome,
        rating = 8,
        isFavorite = true
    )

    private fun fixtureIndex(): SearchIndex {
        val index = SearchIndex()
        index.rebuild(
            sessions = listOf(
                session("s:acid", "Acid mountain", "calm", "mountain cabin", "visionary peak"),
                session("s:mush", "Mushroom beach", "social", "beach house", "gentle glow")
            ),
            substances = listOf(
                Substance(
                    id = "sub:lsd", name = "LSD", createdAt = 0L, updatedAt = 0L,
                    deviceOrigin = "system", cachedAt = 0L, sourceVersion = "test",
                    summary = "classic psychedelic", aliases = listOf("acid")
                ),
                Substance(
                    id = "sub:shroom", name = "Psilocybin mushrooms", createdAt = 0L, updatedAt = 0L,
                    deviceOrigin = "system", cachedAt = 0L, sourceVersion = "test",
                    summary = "classic psychedelic", aliases = listOf("shrooms")
                )
            ),
            notes = listOf(
                Note(
                    id = "n:trip", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "s:acid", title = "Trip log", body = "acid mountain notes"
                )
            ),
            doses = listOf(
                Dose(
                    id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "s:acid", substanceId = "sub:lsd",
                    routeOfAdministration = "Oral", amount = 100.0, unit = "ug",
                    timestamp = 1720728000000L, notes = "acid tab"
                )
            ),
            timelineEvents = listOf(
                TimelineEvent(
                    id = "e:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "s:acid", timestamp = 1720730000000L,
                    eventType = TimelineEventType.PEAK, label = "peak", body = "peak at two hours"
                )
            ),
            effects = listOf(
                Effect(
                    id = "f:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    name = "Euphoria", description = "classic psychedelic glow",
                    substanceIds = listOf("sub:lsd")
                )
            ),
            substanceNames = mapOf("sub:lsd" to "LSD", "sub:shroom" to "Psilocybin mushrooms")
        )
        return index
    }

    // ---- Verbatim pre-refactor algorithm over an index snapshot ----

    private val tokenSeparator =
        Regex("[\\s,;:.!?()\\[\\]{}<>/\\\\@#\\$%^&*+=|~`\\\"'\\u2013\\u2014]+")

    private fun referenceOldSearch(
        query: String,
        index: Map<String, List<SearchResult>>
    ): List<SearchResult> {
        if (index.isEmpty() || query.isBlank()) return emptyList()

        val terms = query.lowercase()
            .split(tokenSeparator)
            .filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()

        if (terms.size == 1) {
            val term = terms[0]
            val results = index.entries
                .filter { it.key.contains(term) }
                .flatMap { it.value }
            return referenceSortAndDedupe(results)
        }

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
                val distinctTerms = results.map { r ->
                    terms.count { t ->
                        index.entries.any { (w, rs) -> w.contains(t) && rs.any { it.entityId == r.entityId } }
                    }
                }.maxOrNull() ?: 0
                distinctTerms * 100 + (results.maxOfOrNull { it.score } ?: 0)
            }
            .take(50)
            .mapNotNull { (_, results) -> results.maxByOrNull { it.score } }
    }

    private fun referenceSortAndDedupe(results: Iterable<SearchResult>): List<SearchResult> =
        results
            .groupBy { it.entityId }
            .mapNotNull { (_, rs) -> rs.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
            .take(50)

    // ---- Tests ----

    @Test
    fun multiTermOrderingMatchesOldRuleOnMixedFixture() {
        val index = fixtureIndex()
        val snapshot = index.snapshotIndex()
        assertTrue(snapshot.isNotEmpty(), "fixture must actually build an index")

        val queries = listOf(
            "acid",
            "acid mountain",
            "acid mountain peak",
            "classic psychedelic",
            "ACID Mountain!",
            "acid, mountain",
            "mountain classic",
            "trip glow",
            "nothingmatchesthis",
            "a b c",
            " ",
            "peak peak peak"
        )
        for (query in queries) {
            assertEquals(
                referenceOldSearch(query, snapshot), index.search(query),
                "ordering for query '$query' must match the pre-refactor rule exactly"
            )
        }
    }

    @Test
    fun explicitExpectedOrderForMultiTermQuery() {
        // Non-vacuous pin (not just old-vs-new self-consistency): the key is
        // distinctMatchedTerms * 100 + maxScore, ties keep first-encounter
        // order of the index's words.
        val index = fixtureIndex()

        val twoTerm = index.search("acid mountain").map { it.entityId }
        assertEquals(
            listOf("s:acid", "n:trip", "sub:lsd", "d:1"), twoTerm,
            "2 matched terms (210) beat 2 terms + score 5 (205) beat 1 term + 8 (108) beat 1 term + 3 (103)"
        )

        val threeTerm = index.search("acid mountain peak").map { it.entityId }
        assertEquals(
            listOf("s:acid", "n:trip", "sub:lsd", "d:1", "e:1"), threeTerm,
            "'peak' also matches the s:acid outcome and the e:1 label; d:1 and e:1 " +
                "tie at 103 and must keep bucket insertion order (d:1 first)"
        )

        // Tie-break pin: both substances match 'classic psychedelic' with the
        // same key (2 * 100 + 8 = 208); LSD is indexed first, so it must stay
        // first, with the effect (204) after them.
        val tie = index.search("classic psychedelic").map { it.entityId }
        assertEquals(listOf("sub:lsd", "sub:shroom", "f:1"), tie,
            "equal keys must keep bucket insertion order (stable sort tie-break)")
    }

    @Test
    fun singleTermCapsAndDedupeStayFifty() {
        val index = SearchIndex()
        val notes = (1..120).map { i ->
            Note(
                id = "n:$i", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                sessionId = null, title = "megawidget $i", body = "megawidget body $i"
            )
        }
        index.rebuild(
            sessions = emptyList(), substances = emptyList(), notes = notes,
            doses = emptyList(), timelineEvents = emptyList(), effects = emptyList(),
            substanceNames = emptyMap()
        )
        val snapshot = index.snapshotIndex()
        assertEquals(50, index.search("megawidget").size, "take(50) cap preserved")
        assertEquals(referenceOldSearch("megawidget", snapshot), index.search("megawidget"))
    }

    @Test
    fun scoringRunsOnSnapshotAndSurvivesConcurrentMutation() {
        // Single-threaded simulation of the wave4 hazard: snapshot taken, the
        // live index is then rebuilt out from under the scorer, and scoring
        // must still succeed purely on the snapshot (the old code read the
        // live index inside its comparator while the repo lock was held, so a
        // rebuild racing it could throw or rank against half-built state).
        val index = fixtureIndex()
        val snapshot = index.snapshotIndex()

        index.rebuild(
            sessions = emptyList(),
            substances = listOf(
                Substance(
                    id = "sub:other", name = "Completely different", createdAt = 0L, updatedAt = 0L,
                    deviceOrigin = "system", cachedAt = 0L, sourceVersion = "test",
                    summary = "nothing alike", aliases = emptyList()
                )
            ),
            notes = emptyList(), doses = emptyList(), timelineEvents = emptyList(),
            effects = emptyList(), substanceNames = emptyMap()
        )

        val result = scoreSearchSnapshot("acid mountain", snapshot)
        assertEquals(referenceOldSearch("acid mountain", snapshot), result,
            "scoring must depend only on the passed snapshot, never on live index state")
        assertTrue(result.any { it.entityId == "s:acid" },
            "the pre-mutation snapshot content is what gets ranked")

        // And the live index now answers with the NEW content only.
        assertTrue(index.search("acid").isEmpty(), "rebuilt index dropped the old entities")
        assertTrue(index.search("completely").isNotEmpty(), "rebuilt index answers from new content")
    }
}
