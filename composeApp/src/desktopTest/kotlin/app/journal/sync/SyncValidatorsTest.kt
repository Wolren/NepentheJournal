package app.journal.sync

import app.journal.model.*
import kotlin.test.*

/**
 * Tests for the actual [validateSyncBatch] and [validateWsDelta] functions
 * that run on the server/peer side during sync.
 *
 * These test the PRODUCTION validators directly, not a simulation.
 */
class SyncValidatorsTest {

    private fun session(id: String) = Session(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        title = "Session $id", startTime = now
    )

    private fun dose(id: String, sessionId: String) = Dose(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        sessionId = sessionId, substanceId = "cid:1",
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = now
    )

    private fun substance(id: String, name: String) = Substance(
        id = id, name = name, createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceClass = listOf("Test"), cachedAt = now, sourceVersion = "test"
    )

    private fun interaction(id: String) = Interaction(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceAId = "cid:1", substanceBId = "cid:2", riskLevel = InteractionRisk.UNSAFE
    )

    private fun event(id: String, sessionId: String) = TimelineEvent(
        id = id, sessionId = sessionId, timestamp = now,
        eventType = TimelineEventType.ONSET, label = "Start",
        createdAt = now, updatedAt = now, deviceOrigin = "test"
    )

    private fun effect(id: String) = Effect(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        name = "Euphoria", substanceIds = listOf("cid:1")
    )

    private fun customUnit(id: String) = CustomUnit(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceId = "cid:1", name = "tabs"
    )

    private fun note(id: String) = Note(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        sessionId = "s:1", body = "Note body"
    )

    // ==================== SyncBatch validation ====================

    @Test
    fun validSyncBatchPasses() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1")),
            doses = listOf(dose("d:1", "s:1")),
            substances = listOf(substance("cid:1", "LSD")),
            interactions = listOf(interaction("i:1")),
            timelineEvents = listOf(event("e:1", "s:1")),
            effects = listOf(effect("ef:1")),
            customUnits = listOf(customUnit("u:1")),
            notes = listOf(note("n:1"))
        )
        assertNull(validateSyncBatch(batch))
    }

    @Test
    fun emptyListsPass() {
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L)
        assertNull(validateSyncBatch(batch))
    }

    @Test
    fun sessionTitleTooLongRejected() {
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(title = "A".repeat(501))))
        assertNotNull(validateSyncBatch(batch))
    }

    @Test
    fun sessionTitleAtBoundaryAccepted() {
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(title = "A".repeat(500))))
        assertNull(validateSyncBatch(batch))
    }

    @Test
    fun sessionIdTooLongRejected() {
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("a".repeat(129))))
        assertNotNull(validateSyncBatch(batch))
    }

    @Test
    fun sessionRatingOutOfRangeRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 0)))))
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 11)))))
    }

    @Test
    fun sessionRatingBoundaryAccepted() {
        assertNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 1)))))
        assertNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 10)))))
    }

    @Test
    fun doseAmountNegativeRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(amount = -1.0)))))
    }

    @Test
    fun doseAmountAtUpperLimitAccepted() {
        assertNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(amount = 1_000_000.0)))))
    }

    @Test
    fun doseAmountOverUpperRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(amount = 1_000_001.0)))))
    }

    @Test
    fun doseROATooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(routeOfAdministration = "A".repeat(51))))))
    }

    @Test
    fun doseUnitTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(unit = "A".repeat(21))))))
    }

    @Test
    fun doseIdTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("a".repeat(129), "s:1")))))
    }

    // ==================== Count limits ====================

    @Test
    fun tooManySessionsRejected() {
        val sessions = (1..501).map { session("s:$it") }
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L, sessions = sessions)))
    }

    @Test
    fun tooManySubstancesRejected() {
        val substances = (1..101).map { substance("cid:$it", "Sub-$it") }
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L, substances = substances)))
    }

    @Test
    fun tooManyEffectsRejected() {
        val effects = (1..101).map { effect("ef:$it") }
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L, effects = effects)))
    }

    @Test
    fun tooManyCustomUnitsRejected() {
        val units = (1..101).map { customUnit("u:$it") }
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L, customUnits = units)))
    }

    @Test
    fun tooManyInteractionsRejected() {
        val interactions = (1..101).map { interaction("i:$it") }
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L, interactions = interactions)))
    }

    // ==================== Entity-specific field validation ====================

    @Test
    fun noteBodyTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            notes = listOf(note("n:1").copy(body = "A".repeat(65537))))))
    }

    @Test
    fun substanceNameTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            substances = listOf(substance("cid:1", "A".repeat(201))))))
    }

    @Test
    fun interactionSubstanceIdTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            interactions = listOf(interaction("i:1").copy(substanceAId = "a".repeat(129))))))
    }

    @Test
    fun timelineEventLabelTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            timelineEvents = listOf(event("e:1", "s:1").copy(label = "A".repeat(201))))))
    }

    @Test
    fun effectNameTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            effects = listOf(effect("ef:1").copy(name = "A".repeat(201))))))
    }

    @Test
    fun effectSubstanceIdTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            effects = listOf(effect("ef:1").copy(substanceIds = listOf("a".repeat(129)))))))
    }

    @Test
    fun customUnitNameTooLongRejected() {
        assertNotNull(validateSyncBatch(SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            customUnits = listOf(customUnit("u:1").copy(name = "A".repeat(101))))))
    }

    // ==================== WsDelta validation ====================

    @Test
    fun validWsDeltaPasses() {
        val delta = WsDelta(seq = 1L,
            sessions = listOf(session("s:1")),
            doses = listOf(dose("d:1", "s:1")),
            substances = listOf(substance("cid:1", "LSD"))
        )
        assertNull(validateWsDelta(delta))
    }

    @Test
    fun emptyWsDeltaPasses() {
        assertNull(validateWsDelta(WsDelta(seq = 1L)))
    }

    @Test
    fun wsDeltaTooManySessionsRejected() {
        val sessions = (1..501).map { session("s:$it") }
        assertNotNull(validateWsDelta(WsDelta(seq = 1L, sessions = sessions)))
    }

    @Test
    fun wsDeltaTooManyEffectsRejected() {
        val effects = (1..101).map { effect("ef:$it") }
        assertNotNull(validateWsDelta(WsDelta(seq = 1L, effects = effects)))
    }

    @Test
    fun wsDeltaIdTooLongRejected() {
        assertNotNull(validateWsDelta(WsDelta(seq = 1L,
            sessions = listOf(session("a".repeat(129))))))
    }

    @Test
    fun wsDeltaDoseAmountNegativeRejected() {
        assertNotNull(validateWsDelta(WsDelta(seq = 1L,
            doses = listOf(dose("d:1", "s:1").copy(amount = -1.0)))))
    }

    @Test
    fun wsDeltaNoteBodyTooLongRejected() {
        assertNotNull(validateWsDelta(WsDelta(seq = 1L,
            notes = listOf(note("n:1").copy(body = "A".repeat(65537))))))
    }

    @Test
    fun wsDeltaCustomUnitNameTooLongRejected() {
        assertNotNull(validateWsDelta(WsDelta(seq = 1L,
            customUnits = listOf(customUnit("u:1").copy(name = "A".repeat(101))))))
    }

    companion object {
        private const val now = 1_000_000L
    }
}
