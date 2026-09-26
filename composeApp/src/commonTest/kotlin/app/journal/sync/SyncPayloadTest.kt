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
        assertTrue(encoded.contains("\"sessions\""), "should contain sessions key in any batch")
        assertTrue(encoded.contains("\"doses\""), "should contain doses key in any batch")
    }

    @Test
    fun syncResponseRoundtrip() {
        val response = SyncResponse(
            success = true,
            sessions = listOf(session("s:1")),
            doses = listOf(dose("d:1", "s:1"))
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<SyncResponse>(encoded)
        assertTrue(decoded.success)
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
        val sessions = (1..500).map { session("s:$it") }
        val batch = SyncBatch(
            deviceId = "d", deviceName = "n", since = 0L,
            sessions = sessions
        )
        val encoded = json.encodeToString(batch)
        val decoded = json.decodeFromString<SyncBatch>(encoded)
        assertEquals(500, decoded.sessions.size)
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

    // ==================== Composite pull cursor ====================

    @Test
    fun syncResponseNextSinceIdRoundtrip() {
        val response = SyncResponse(
            success = true,
            interactions = listOf(interaction("i:99")),
            truncated = true,
            nextSince = 1_783_942_757_808L,
            nextSinceId = "int:99"
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<SyncResponse>(encoded)
        assertTrue(decoded.truncated)
        assertEquals(1_783_942_757_808L, decoded.nextSince)
        assertEquals("int:99", decoded.nextSinceId,
            "the id half of the composite cursor must survive the wire")
    }

    @Test
    fun syncResponseDecodesLegacyJsonWithoutNextSinceId() {
        // A peer running an older build never sends nextSinceId; decoding
        // must default it to "" so the client falls back to its timestamp
        // drain instead of failing the whole response.
        val legacy = """{"success":true,"truncated":true,"nextSince":1234}"""
        val decoded = json.decodeFromString<SyncResponse>(legacy)
        assertTrue(decoded.truncated)
        assertEquals(1234L, decoded.nextSince)
        assertEquals("", decoded.nextSinceId)
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
}
