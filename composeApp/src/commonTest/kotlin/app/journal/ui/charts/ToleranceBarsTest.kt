package app.journal.ui.charts

import app.journal.data.ToleranceLevel
import app.journal.model.Dose
import app.journal.model.Substance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToleranceBarsTest {

    private val dayMs = 86_400_000L
    private val here = 1_700_000_000_000L

    private val substances = mapOf(
        "sub:1" to Substance(
            id = "sub:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = "LSD", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test",
        )
    )

    private fun dose(id: String, substanceId: String, timestamp: Long) = Dose(
        id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        sessionId = "s:$id", substanceId = substanceId,
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg",
        timestamp = timestamp,
    )

    @Test
    fun noDosesYieldsNoRows() {
        assertTrue(buildToleranceRows(emptyList(), here, substances).isEmpty())
    }

    @Test
    fun singleRecentDoseDecaysFromMedium() {
        val rows = buildToleranceRows(
            listOf(dose("d:1", "sub:1", here - 5 * dayMs)),
            here,
            substances,
        )
        assertEquals(1, rows.size)
        assertEquals("LSD", rows[0].name)
        // Day 25's end is the first one that actually reaches the dose (the
        // day-end timestamp sits a millisecond short of a clean day boundary),
        // and a lone recent dose is MEDIUM there, staying MEDIUM to the end.
        assertEquals(listOf(ToleranceSegment(25, 30, ToleranceLevel.MEDIUM)), rows[0].segments)
    }

    @Test
    fun twoRecentDosesAreHighAtTheEnd() {
        val rows = buildToleranceRows(
            listOf(
                dose("d:1", "sub:1", here - 2 * dayMs),
                dose("d:2", "sub:1", here - 1 * dayMs),
            ),
            here,
            substances,
        )
        assertEquals(1, rows.size)
        // Two doses inside three days is the HIGH branch; days before both
        // doses are reachable have no segment at all.
        assertEquals(listOf(ToleranceSegment(28, 30, ToleranceLevel.HIGH)), rows[0].segments)
    }

    @Test
    fun dosesAfterTheSessionNeverCount() {
        // A dose from the "future" relative to the session must not paint
        // tolerance bars onto the past.
        val rows = buildToleranceRows(
            listOf(dose("d:1", "sub:1", here + 2 * dayMs)),
            here,
            substances,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun dosesOutsideTheLookbackAreDropped() {
        val rows = buildToleranceRows(
            listOf(dose("d:1", "sub:1", here - 200 * dayMs)),
            here,
            substances,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun longWindowReachesTheOldDecayTail() {
        // A 90-day window has to reach back far enough: the dose sits 10 days
        // before "here", so only the tail of the window may show anything -
        // MEDIUM for a week, then LOW until the dose falls out of range.
        val rows = buildToleranceRows(
            listOf(dose("d:1", "sub:1", here - 10 * dayMs)),
            here,
            substances,
            days = 90,
        )
        assertEquals(1, rows.size)
        assertEquals(
            listOf(
                ToleranceSegment(80, 87, ToleranceLevel.MEDIUM),
                ToleranceSegment(87, 90, ToleranceLevel.LOW),
            ),
            rows[0].segments,
        )
    }

    @Test
    fun recentMostActiveSubstanceComesFirstAndRowsAreCapped() {
        val doses = mutableListOf<Dose>()
        val map = mutableMapOf<String, Substance>()
        // Seven substances, each dosed a day before "here" on a different day
        // so every one of them has active tolerance.
        for (i in 1..7) {
            val id = "sub:$i"
            map[id] = Substance(
                id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                name = "Sub$i", substanceClass = listOf("Class"),
                cachedAt = 0L, sourceVersion = "test",
            )
            doses.add(dose("d:$i", id, here - i * dayMs))
        }
        val rows = buildToleranceRows(doses, here, map)
        assertEquals(6, rows.size, "capped at six rows")
        // Most recent activity first.
        assertEquals("Sub1", rows[0].name)
        assertEquals("Sub6", rows[5].name)
    }
}
