package app.journal.sync

import app.journal.model.*
import kotlin.test.*

/**
 * Tests for the shared push chunking and cursor discipline
 * (commonMain SyncChunking.kt, contract section d).
 *
 * Proves audit C1 is fixable at the shared layer: an oversized batch
 * (2015 interactions against a cap of 100) slices into cap-respecting
 * pieces whose union is exactly the input, and the cursor only advances
 * when every slice acked successfully.
 */
class SyncChunkingTest {

    private val now = 1_700_000_000_000L

    private fun interaction(i: Int) = Interaction(
        id = "i:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceAId = "cid:1", substanceBId = "cid:2", riskLevel = InteractionRisk.LOW
    )

    private fun session(i: Int) = Session(
        id = "s:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        title = "Session $i", startTime = now
    )

    private fun dose(i: Int) = Dose(
        id = "d:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        sessionId = "s:1", substanceId = "cid:1",
        routeOfAdministration = "Oral", amount = 10.0, unit = "mg", timestamp = now
    )

    private fun substance(i: Int) = Substance(
        id = "cid:$i", name = "Sub-$i", createdAt = now, updatedAt = now,
        deviceOrigin = "test", cachedAt = now, sourceVersion = "test"
    )

    private fun effect(i: Int) = Effect(
        id = "ef:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        name = "Effect-$i", substanceIds = listOf("cid:1")
    )

    private fun note(i: Int) = Note(
        id = "n:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        sessionId = "s:1", body = "Body $i"
    )

    private fun event(i: Int) = TimelineEvent(
        id = "e:$i", sessionId = "s:1", timestamp = now,
        eventType = TimelineEventType.ONSET, label = "Start",
        createdAt = now, updatedAt = now, deviceOrigin = "test"
    )

    private fun unit(i: Int) = CustomUnit(
        id = "u:$i", createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceId = "cid:1", name = "unit-$i"
    )

    @Test
    fun `2015 interactions slice into cap-sized pieces with disjoint ids`() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 42L,
            interactions = (0 until 2015).map { interaction(it) }
        )
        val slices = buildPushSlices(batch, SyncChunkLimits())

        // ceil(2015 / 100) == 21 slices, each within the validator cap.
        assertEquals(21, slices.size, "2015 interactions at cap 100 must produce 21 slices")
        assertTrue(slices.all { it.interactions.size <= SyncLimits.MAX_INTERACTIONS },
            "every slice must respect MAX_INTERACTIONS")
        assertTrue(slices.all { it.interactions.isNotEmpty() }, "no empty middle slice expected")

        // Disjoint ids per slice, union equals the input, order preserved.
        val flattened = slices.flatMap { it.interactions }
        assertEquals(batch.interactions.size, flattened.size, "no entity lost or duplicated")
        assertEquals(flattened.map { it.id }.toSet().size, flattened.size, "ids must be disjoint across slices")
        assertEquals(batch.interactions.map { it.id }, flattened.map { it.id },
            "concatenation must reproduce the input order")

        // Metadata rides on every slice; untouched collections stay empty.
        assertTrue(slices.all { it.deviceId == "d" && it.deviceName == "n" && it.since == 42L })
        assertTrue(slices.all {
            it.sessions.isEmpty() && it.doses.isEmpty() && it.substances.isEmpty() &&
                it.effects.isEmpty() && it.notes.isEmpty() && it.timelineEvents.isEmpty() &&
                it.customUnits.isEmpty() && it.deletedSessionIds.isEmpty()
        })

        // Every slice passes the real validator (the server-side gate).
        slices.forEach { assertNull(validateSyncBatch(it), "slice must pass validateSyncBatch") }
    }

    @Test
    fun `mixed batch respects every collection cap`() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = (0 until 651).map { session(it) },
            doses = (0 until 502).map { dose(it) },
            substances = (0 until 1201).map { substance(it) },
            effects = (0 until 101).map { effect(it) },
            interactions = (0 until 250).map { interaction(it) },
            notes = (0 until 503).map { note(it) },
            timelineEvents = (0 until 501).map { event(it) },
            customUnits = (0 until 101).map { unit(it) },
            deletedSessionIds = (0 until 520).map { "gone-s:$it" },
            deletedDoseIds = (0 until 3).map { "gone-d:$it" }
        )

        val slices = buildPushSlices(batch, SyncChunkLimits())
        assertTrue(slices.isNotEmpty())

        for (slice in slices) {
            assertTrue(slice.sessions.size <= SyncLimits.MAX_ITEMS_DEFAULT, "sessions over cap")
            assertTrue(slice.doses.size <= SyncLimits.MAX_ITEMS_DEFAULT, "doses over cap")
            assertTrue(slice.substances.size <= SyncLimits.MAX_SUBSTANCES, "substances over cap")
            assertTrue(slice.effects.size <= SyncLimits.MAX_EFFECTS, "effects over cap")
            assertTrue(slice.interactions.size <= SyncLimits.MAX_INTERACTIONS, "interactions over cap")
            assertTrue(slice.notes.size <= SyncLimits.MAX_ITEMS_DEFAULT, "notes over cap")
            assertTrue(slice.timelineEvents.size <= SyncLimits.MAX_ITEMS_DEFAULT, "events over cap")
            assertTrue(slice.customUnits.size <= SyncLimits.MAX_CUSTOM_UNITS, "custom units over cap")
            assertTrue(slice.deletedSessionIds.size <= SyncLimits.MAX_ITEMS_DEFAULT, "deleted sessions over cap")
            assertTrue(slice.deletedDoseIds.size <= SyncLimits.MAX_ITEMS_DEFAULT, "deleted doses over cap")
            assertNull(validateSyncBatch(slice), "slice must pass the unified validator")
        }

        // Union equals input for every collection.
        assertEquals(batch.sessions.map { it.id }, slices.flatMap { it.sessions }.map { it.id })
        assertEquals(batch.doses.map { it.id }, slices.flatMap { it.doses }.map { it.id })
        assertEquals(batch.substances.map { it.id }, slices.flatMap { it.substances }.map { it.id })
        assertEquals(batch.effects.map { it.id }, slices.flatMap { it.effects }.map { it.id })
        assertEquals(batch.interactions.map { it.id }, slices.flatMap { it.interactions }.map { it.id })
        assertEquals(batch.notes.map { it.id }, slices.flatMap { it.notes }.map { it.id })
        assertEquals(batch.timelineEvents.map { it.id }, slices.flatMap { it.timelineEvents }.map { it.id })
        assertEquals(batch.customUnits.map { it.id }, slices.flatMap { it.customUnits }.map { it.id })
        assertEquals(batch.deletedSessionIds, slices.flatMap { it.deletedSessionIds })
        assertEquals(batch.deletedDoseIds, slices.flatMap { it.deletedDoseIds })
    }

    @Test
    fun `empty batch yields a single empty slice`() {
        // Documented choice: an empty batch still produces exactly one slice
        // so callers can send slices uniformly and gate the cursor on acks.
        val slices = buildPushSlices(SyncBatch(deviceId = "d", deviceName = "n", since = 0L))
        assertEquals(1, slices.size)
        val only = slices.single()
        assertTrue(only.interactions.isEmpty() && only.sessions.isEmpty() &&
            only.deletedSessionIds.isEmpty() && only.customUnits.isEmpty())
        assertNull(validateSyncBatch(only))
    }

    @Test
    fun `cursor advances only when every slice acked successfully`() {
        val acksOk = listOf(SyncResponse(success = true), SyncResponse(success = true))
        val acksOneFailure = listOf(
            SyncResponse(success = true),
            SyncResponse(success = false, error = "Too many interactions (max 100)")
        )

        assertTrue(allSucceeded(acksOk))
        assertFalse(allSucceeded(acksOneFailure), "a single failed slice blocks the cursor")
        assertFalse(allSucceeded(emptyList()), "no acks means nothing was confirmed")

        assertEquals(5000L, advanceCursorIfAllSucceeded(1000L, 5000L, acksOk))
        assertEquals(1000L, advanceCursorIfAllSucceeded(1000L, 5000L, acksOneFailure),
            "cursor must stay at the previous value on any failure")
        assertEquals(1000L, advanceCursorIfAllSucceeded(1000L, 5000L, emptyList()),
            "cursor must not move without acks")
    }

    @Test
    fun `chunk caps below one are rejected instead of looping forever`() {
        assertFails {
            buildPushSlices(
                SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
                    interactions = listOf(interaction(0))),
                SyncChunkLimits(maxInteractions = 0)
            )
        }
    }
}
