package app.journal.data.repository

import app.journal.model.Session
import app.journal.util.Hlc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 merge laws. The field merge is supposed to be a join: idempotent,
 * commutative and associative. The test drives random clocked rows, so a change to the
 * stamp, identity or tiebreak rules that breaks a law fails here before it reaches a
 * sync path.
 *
 * Mixed pre-v10 pairs are excluded on purpose: their order falls back to updatedAt and
 * device origin, which is a total order only per pair (see FieldRegisters KDoc).
 */
class MergeLawPropertyTest {

    private val seed = 20261121L
    private val iterations = 200

    private fun Random.clock(): String =
        Hlc.of(1_000_000L + nextLong(100_000L), nextLong(0L, 4L)).encoded

    private fun Random.session(): Session {
        val fieldNames = listOf(
            "title", "outcome", "notes", "set", "setting", "intention",
            "tags", "rating", "isFavorite", "pausedMs", "startTime",
        )
        val fieldClocks = fieldNames.filter { nextBoolean() }.associateWith { clock() }
        return Session(
            id = "s:1",
            createdAt = 1L + nextLong(1_000_000L),
            updatedAt = 1L + nextLong(1_000_000L),
            deviceOrigin = listOf("a", "b", "c")[nextInt(3)],
            clock = clock(),
            fieldClocks = fieldClocks,
            title = "title ${nextInt(5)}",
            startTime = 1_000L + nextLong(1_000_000L),
            outcome = if (nextBoolean()) "outcome ${nextInt(5)}" else null,
            notes = if (nextBoolean()) "notes ${nextInt(5)}" else null,
            set = if (nextBoolean()) "set ${nextInt(5)}" else null,
            setting = if (nextBoolean()) "setting ${nextInt(5)}" else null,
            intention = if (nextBoolean()) "intention ${nextInt(5)}" else null,
            tags = if (nextBoolean()) listOf("tag${nextInt(5)}") else emptyList(),
            rating = nextInt(1, 11),
            isFavorite = nextBoolean(),
            pausedMs = nextLong(0L, 10_000L),
        )
    }

    @Test
    fun mergeIsIdempotent() {
        val random = Random(seed)
        repeat(iterations) {
            val row = random.session()
            assertEquals(row, FieldRegisters.merge(row, row, Session.serializer()))
        }
    }

    @Test
    fun mergeIsCommutative() {
        val random = Random(seed + 1)
        repeat(iterations) {
            val left = random.session()
            val right = random.session()
            assertEquals(
                FieldRegisters.merge(left, right, Session.serializer()),
                FieldRegisters.merge(right, left, Session.serializer()),
            )
        }
    }

    @Test
    fun mergeIsAssociative() {
        val random = Random(seed + 2)
        repeat(iterations) {
            val a = random.session()
            val b = random.session()
            val c = random.session()
            val left = FieldRegisters.merge(
                FieldRegisters.merge(a, b, Session.serializer()), c, Session.serializer(),
            )
            val right = FieldRegisters.merge(
                a, FieldRegisters.merge(b, c, Session.serializer()), Session.serializer(),
            )
            assertEquals(left, right)
        }
    }

    @Test
    fun everyMergeOrderConverges() {
        val random = Random(seed + 3)
        repeat(iterations / 4) {
            val rows = listOf(random.session(), random.session(), random.session())
            val expected = FieldRegisters.merge(
                FieldRegisters.merge(rows[0], rows[1], Session.serializer()),
                rows[2],
                Session.serializer(),
            )
            for (order in permutations(rows)) {
                var merged = order[0]
                for (next in order.drop(1)) {
                    merged = FieldRegisters.merge(merged, next, Session.serializer())
                }
                assertEquals(expected, merged, "all six orders must reach the same row")
            }
        }
    }

    @Test
    fun theMergeKeepsTheMaximumRowClock() {
        val random = Random(seed + 4)
        repeat(iterations) {
            val left = random.session()
            val right = random.session()
            val merged = FieldRegisters.merge(left, right, Session.serializer())
            val mergedClock = requireNotNull(Hlc.parseOrNull(merged.clock))
            assertTrue(mergedClock >= Hlc.parseOrNull(left.clock)!!)
            assertTrue(mergedClock >= Hlc.parseOrNull(right.clock)!!)
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.indices.flatMap { index ->
            val rest = items.toMutableList().also { it.removeAt(index) }
            permutations(rest).map { listOf(items[index]) + it }
        }
}
