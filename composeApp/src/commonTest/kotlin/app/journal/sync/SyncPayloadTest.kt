package app.journal.sync

import app.journal.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SyncPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val now = 1_000_000L

    @Test
    fun syncBatchRoundtrip() {
        val batch = SyncBatch(
            deviceId = "test-device",
            deviceName = "Test Device",
            since = 500L,
            sessions = listOf(session("s:1")),
            doses = listOf(dose("d:1", "s:1")),
            substances = listOf(substance("cid:1", "LSD")),
            interactions = listOf(interaction("i:1")),
            timelineEvents = listOf(event("e:1", "s:1"))
        )
        val encoded = json.encodeToString(batch)
        val decoded = json.decodeFromString<SyncBatch>(encoded)

        assertEquals(batch.deviceId, decoded.deviceId)
        assertEquals(1, decoded.sessions.size)
        assertEquals(1, decoded.doses.size)
        assertEquals(1, decoded.substances.size)
        assertEquals(1, decoded.timelineEvents.size)
        assertEquals("s:1", decoded.sessions.first().id)
    }

    @Test
    fun syncBatchEmptyDefaults() {
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L)
        val encoded = json.encodeToString(batch)
        assertTrue(encoded.contains("\"deviceId\""))
        assertTrue(encoded.contains("\"sessions\"") || encoded.contains("\"doses\""))
    }

    @Test
    fun syncResponseRoundtrip() {
        val response = SyncResponse(
            success = true,
            sessions = listOf(session("s:1")),
            doses = listOf(dose("d:1", "s:1")),
            conflictsCreated = 2
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<SyncResponse>(encoded)
        assertTrue(decoded.success)
        assertEquals(2, decoded.conflictsCreated)
        assertEquals(1, decoded.sessions.size)
    }

    @Test
    fun syncResponseError() {
        val response = SyncResponse(success = false, error = "Authentication failed")
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<SyncResponse>(encoded)
        assertFalse(decoded.success)
        assertEquals("Authentication failed", decoded.error)
    }

    @Test
    fun hostInfoRoundtrip() {
        val info = HostInfo(
            deviceId = "dev-1", deviceName = "Desktop",
            fingerprint = "abc123", protocolVersion = 2
        )
        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<HostInfo>(encoded)
        assertEquals("dev-1", decoded.deviceId)
        assertEquals(2, decoded.protocolVersion)
    }

    @Test
    fun syncBatchMaxBoundaryIsSerializable() {
        // 500 sessions (max allowed by validateBatch)
        val sessions = (1..500).map { session("s:$it") }
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = sessions
        )
        val encoded = json.encodeToString(batch)
        assertTrue(encoded.length > 1000)
        val decoded = json.decodeFromString<SyncBatch>(encoded)
        assertEquals(500, decoded.sessions.size)
    }

    // ==================== Field validation (mimics KtorSyncServer.validateBatch) ====================

    @Test
    fun sessionValidationCatchesOversizedFields() {
        val tooLongTitle = "A".repeat(501)
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(title = tooLongTitle))
        )
        val error = validateBatchSimulation(batch)
        assertNotNull(error)
        assertTrue(error!!.contains("title too long"))
    }

    @Test
    fun sessionValidationAcceptsValidMax() {
        val maxTitle = "A".repeat(500)
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(title = maxTitle))
        )
        assertNull(validateBatchSimulation(batch))
    }

    @Test
    fun doseAmountOutOfRangeIsRejected() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(amount = -1.0))
        )
        assertNotNull(validateBatchSimulation(batch))
    }

    @Test
    fun doseAmountAtUpperLimitIsAccepted() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(dose("d:1", "s:1").copy(amount = 1_000_000.0))
        )
        assertNull(validateBatchSimulation(batch))
    }

    @Test
    fun tooManySessionsIsRejected() {
        val sessions = (1..501).map { session("s:$it") }
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L, sessions = sessions)
        assertNotNull(validateBatchSimulation(batch))
    }

    @Test
    fun tooManySubstancesIsRejected() {
        val substances = (1..101).map { substance("cid:$it", "Sub-$it") }
        val batch = SyncBatch(deviceId = "d", deviceName = "n", since = 0L, substances = substances)
        assertNotNull(validateBatchSimulation(batch))
    }

    @Test
    fun invalidRatingIsRejected() {
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 0))
        )
        assertNotNull(validateBatchSimulation(batch))
        val batch2 = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(rating = 11))
        )
        assertNotNull(validateBatchSimulation(batch2))
    }

    @Test
    fun tooManyTagsIsRejected() {
        val tags = (1..51).map { "tag-$it" }
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session("s:1").copy(tags = tags))
        )
        assertNotNull(validateBatchSimulation(batch))
    }

    @Test
    fun idTooLongIsRejected() {
        val longId = "a".repeat(129)
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(session(longId))
        )
        assertNotNull(validateBatchSimulation(batch))
    }

    // ==================== Conflict resolution ====================

    @Test
    fun conflictSiblingSerialization() {
        val sibling = ConflictSibling(body = "Remote version", deviceOrigin = "other-device", updatedAt = 2000L)
        val encoded = json.encodeToString(sibling)
        val decoded = json.decodeFromString<ConflictSibling>(encoded)
        assertEquals("Remote version", decoded.body)
        assertEquals("other-device", decoded.deviceOrigin)
    }

    @Test
    fun noteWithConflictSiblingsRoundtrip() {
        val note = Note(
            id = "n:1", createdAt = now, updatedAt = now, deviceOrigin = "test",
            sessionId = "s:1", body = "Original",
            conflictSiblings = listOf(
                ConflictSibling("Conflict body", "other-device", 2000L)
            )
        )
        val encoded = json.encodeToString(note)
        val decoded = json.decodeFromString<Note>(encoded)
        assertEquals(1, decoded.conflictSiblings.size)
        assertEquals("Conflict body", decoded.conflictSiblings.first().body)
    }

    // ==================== Helpers ====================

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

    /**
     * Mirrors the validation logic in KtorSyncServerJvm.kt validateBatch().
     * Kept in sync — if the server validation changes, update this.
     */
    private fun validateBatchSimulation(batch: SyncBatch): String? {
        val maxItems = 500
        if (batch.sessions.size > maxItems) return "Too many sessions (max $maxItems)"
        if (batch.doses.size > maxItems) return "Too many doses (max $maxItems)"
        if (batch.substances.size > 100) return "Too many substances (max 100)"
        if (batch.notes.size > maxItems) return "Too many notes (max $maxItems)"
        if (batch.timelineEvents.size > maxItems) return "Too many events (max $maxItems)"
        if (batch.interactions.size > 100) return "Too many interactions (max 100)"

        val maxFieldLen = 65536
        for (s in batch.sessions) {
            if (s.id.length > 128) return "Session ID too long"
            if (s.title.length > 500) return "Session title too long"
            if ((s.set?.length ?: 0) > maxFieldLen) return "Session set too long"
            if ((s.setting?.length ?: 0) > maxFieldLen) return "Session setting too long"
            if ((s.intention?.length ?: 0) > maxFieldLen) return "Session intention too long"
            if ((s.outcome?.length ?: 0) > maxFieldLen) return "Session outcome too long"
            if (s.tags.size > 50) return "Too many session tags"
            if (s.tags.any { it.length > 100 }) return "Session tag too long"
            if (s.rating != null && (s.rating < 1 || s.rating > 10)) return "Invalid rating"
        }
        for (d in batch.doses) {
            if (d.id.length > 128) return "Dose ID too long"
            if (d.sessionId.length > 128) return "Dose sessionId too long"
            if (d.substanceId.length > 128) return "Dose substanceId too long"
            if (d.routeOfAdministration.length > 50) return "Invalid ROA length"
            if (d.unit.length > 20) return "Invalid unit length"
            if (d.amount < 0 || d.amount > 1_000_000) return "Invalid dose amount"
            if (d.notes?.length ?: 0 > maxFieldLen) return "Dose notes too long"
        }
        for (s in batch.substances) {
            if (s.id.length > 128) return "Substance ID too long"
            if (s.name.length > 200) return "Substance name too long"
        }
        for (i in batch.interactions) {
            if (i.id.length > 128) return "Interaction ID too long"
        }
        for (t in batch.timelineEvents) {
            if (t.id.length > 128) return "TimelineEvent ID too long"
            if (t.label.length > 200) return "TimelineEvent label too long"
        }
        return null
    }
}
