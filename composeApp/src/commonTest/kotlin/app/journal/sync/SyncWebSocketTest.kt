package app.journal.sync

import app.journal.model.*
import kotlin.test.*
import kotlinx.serialization.json.Json

class SyncWebSocketTest {

    private val json = wsJson

    @Test
    fun wsPingSerializationRoundtrip() {
        val original = WsPing(seq = 42)
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsPing
        assertEquals(original.seq, decoded.seq)
    }

    @Test
    fun wsPingJsonIncludesTypeDiscriminator() {
        val jsonStr = json.encodeToString(WsMessage.serializer(), WsPing(seq = 1))
        assertTrue(jsonStr.contains("\"#type\":\"ws:ping\""))
    }

    @Test
    fun wsPongSerializationRoundtrip() {
        val original = WsPong(seq = 99)
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsPong
        assertEquals(original.seq, decoded.seq)
    }

    @Test
    fun wsPongJsonIncludesTypeDiscriminator() {
        val jsonStr = json.encodeToString(WsMessage.serializer(), WsPong(seq = 5))
        assertTrue(jsonStr.contains("\"#type\":\"ws:pong\""))
    }

    @Test
    fun wsAckSerializationRoundtrip() {
        val original = WsAck(seq = 10, error = null)
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsAck
        assertEquals(original.seq, decoded.seq)
        assertNull(decoded.error)
    }

    @Test
    fun wsAckWithErrorRoundtrip() {
        val original = WsAck(seq = 20, error = "Malformed frame")
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsAck
        assertEquals("Malformed frame", decoded.error)
    }

    @Test
    fun wsAckJsonIncludesTypeDiscriminator() {
        val jsonStr = json.encodeToString(WsMessage.serializer(), WsAck(seq = 1))
        assertTrue(jsonStr.contains("\"#type\":\"ws:ack\""))
    }

    @Test
    fun wsDeltaEmptyRoundtrip() {
        val original = WsDelta(seq = 1)
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsDelta
        assertEquals(1, decoded.seq)
        assertTrue(decoded.sessions.isEmpty())
        assertTrue(decoded.doses.isEmpty())
    }

    @Test
    fun wsDeltaWithFullDataRoundtrip() {
        val session = Session(
            id = "s:1", createdAt = 100L, updatedAt = 200L, deviceOrigin = "test",
            title = "Test", startTime = 1000L
        )
        val dose = Dose(
            id = "d:1", createdAt = 100L, updatedAt = 200L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 1000L
        )
        val original = WsDelta(
            seq = 5,
            sessions = listOf(session),
            doses = listOf(dose)
        )
        val jsonStr = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString(WsMessage.serializer(), jsonStr) as WsDelta
        assertEquals(5, decoded.seq)
        assertEquals(1, decoded.sessions.size)
        assertEquals("Test", decoded.sessions.first().title)
        assertEquals(1, decoded.doses.size)
        assertEquals(100.0, decoded.doses.first().amount)
    }

    @Test
    fun wsDeltaJsonIncludesTypeDiscriminator() {
        val jsonStr = json.encodeToString(WsMessage.serializer(), WsDelta(seq = 1))
        assertTrue(jsonStr.contains("\"#type\":\"ws:delta\""))
    }

    @Test
    fun deserializeUnknownTypeUsesIgnoreUnknownKeys() {
        // Because ignoreUnknownKeys = true, a message with an unknown #type
        // should fail gracefully rather than crash
        val unknownJson = """{"#type":"ws:unknown","seq":1}"""
        try {
            json.decodeFromString(WsMessage.serializer(), unknownJson)
            fail("Should throw on unknown polymorphic type")
        } catch (_: Exception) {
            // Expected
        }
    }
}
