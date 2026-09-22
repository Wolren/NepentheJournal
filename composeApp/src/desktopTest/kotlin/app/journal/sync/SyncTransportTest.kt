package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import kotlin.test.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/**
 * Tests for SyncTransport state management, batch validation, and WS message
 * serialization.
 *
 * HONEST COVERAGE MAP (audit C7): [KtorSyncServerIntegrationTest] exercises
 * the production SyncServerRouter endpoints (pairing, push, pull, WS) in
 * process, but NO test in this suite drives SyncTransport.startHosting /
 * stopHosting / syncWith / pairWithPeer end to end against a live server;
 * those remain uncovered production paths. Everything asserted here is state
 * management, shared-validator behavior, and wire serialization only.
 * KtorSyncClient's retry behavior is covered directly in
 * KtorSyncClientRetryTest.
 */
class SyncTransportTest {

    private val testDir = File(
        System.getProperty("java.io.tmpdir"),
        "nepenthe-test-transport-${System.nanoTime()}"
    )

    private val repo = JournalRepository()

    @AfterTest
    fun cleanup() {
        testDir.resolve("trusted-devices.json").delete()
        testDir.delete()
    }

    @Test
    fun trustedDevicesInitiallyEmpty() {
        val transport = SyncTransport(repo, testDir.absolutePath)
        assertTrue(transport.trustedDevices().isEmpty())
    }

    @Test
    fun revokeUnknownDeviceLeavesTrustStoreEmpty() {
        val transport = SyncTransport(repo, testDir.absolutePath)
        transport.revokeDevice("nonexistent")
        assertTrue(transport.trustedDevices().isEmpty(),
            "revoking an unknown id must leave the trust store empty")
    }

    @Test
    fun statusFlowStartsWithDefaults() {
        val status = runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            transport.observeStatus().first()
        }
        assertFalse(status.isHosting)
        assertNull(status.hostAddress)
        assertNull(status.lastSyncAt)
        assertEquals(0, status.pairedDeviceCount)
        assertEquals(0, status.activeConnections.size)
        assertEquals(0, status.pendingConflicts)
        assertNull(status.lastError)
    }

    @Test
    fun connectToUnreachableHostReturnsFailure() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            val result = transport.connectManually(
                host = "127.0.0.1", port = 9999, token = "ABC123"
            )
            assertTrue(result.isFailure)
        }
    }

    // ==================== SyncBatch validation ====================

    @Test
    fun validateBatchAcceptsValidData() {
        val batch = SyncBatch(
            deviceId = "device-abc", deviceName = "TestDevice", since = 1000L,
            sessions = listOf(Session(
                id = "s:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                title = "Valid Session", startTime = 1_700_000_000_000L
            )),
            doses = listOf(Dose(
                id = "d:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "cid:1",
                routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 1_700_000_000_000L
            )),
            substances = listOf(Substance(
                id = "cid:1", name = "LSD", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                substanceClass = listOf("Classical Psychedelic"), routesOfAdministration = listOf("Oral"),
                effects = listOf("Visual"), dosageBands = emptyMap(),
                cachedAt = 0L, sourceVersion = "test"
            ))
        )
        assertNull(validateSyncBatch(batch), "Valid SyncBatch should pass validation")
    }

    @Test
    fun validateBatchRejectsInvalidEntities() {
        // Session with blank ID
        val blankIdSession = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(Session(
                id = "", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                title = "Blank ID", startTime = 1_700_000_000_000L
            ))
        )
        // Blank-ID sessions are rejected: SyncValidation.badId rejects any
        // blank id, so this fixture now must produce an error.
        assertNotNull(validateSyncBatch(blankIdSession),
            "session with a blank id must be rejected by validateSyncBatch")
        // Test with negative dose amount
        val negativeDose = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            doses = listOf(Dose(
                id = "d:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "cid:1",
                routeOfAdministration = "Oral", amount = -50.0, unit = "mg", timestamp = 1_700_000_000_000L
            ))
        )
        // Test with far-future timestamp (not directly validated in validateSyncBatch,
        // so we rely on what the validator actually checks , dose amount)
        // Amount negative should be caught
        assertNotNull(validateSyncBatch(negativeDose),
            "Negative dose amount should be rejected")

        // Session with rating out of range
        val invalidRating = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(Session(
                id = "s:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                title = "Test", startTime = 1_700_000_000_000L, rating = 0
            ))
        )
        assertNotNull(validateSyncBatch(invalidRating),
            "Session with rating 0 should be rejected")

        // ID exceeding max length
        val longId = SyncBatch(deviceId = "d", deviceName = "n", since = 0L,
            sessions = listOf(Session(
                id = "x".repeat(129), createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
                title = "Long ID", startTime = 1_700_000_000_000L
            ))
        )
        assertNotNull(validateSyncBatch(longId),
            "Session with ID length > 128 should be rejected")
    }

    // ==================== WebSocket serialization roundtrips ====================

    @Test
    fun wsPingPongRoundtrip() {
        // Encode WsPing as polymorphic WsMessage (includes discriminator), decode back
        val ping: WsMessage = WsPing(seq = 42L)
        val pingJson = wsJson.encodeToString(WsMessage.serializer(), ping)
        val pingDecoded = wsJson.decodeFromString<WsMessage>(pingJson)
        assertTrue(pingDecoded is WsPing, "Decoded ping should be WsPing")
        assertEquals(42L, (pingDecoded as WsPing).seq, "WsPing seq should match")

        // Encode WsPong, decode as WsMessage, verify it's WsPong
        val pong: WsMessage = WsPong(seq = 43L)
        val pongJson = wsJson.encodeToString(WsMessage.serializer(), pong)
        val pongDecoded = wsJson.decodeFromString<WsMessage>(pongJson)
        assertTrue(pongDecoded is WsPong, "Decoded pong should be WsPong")
        assertEquals(43L, (pongDecoded as WsPong).seq, "WsPong seq should match")

        // Verify the discriminator value is exact (a #type key with the wrong
        // value would pass a contains-only check)
        assertTrue(pingJson.contains("\"#type\":\"ws:ping\""), "WsPing JSON should carry the ws:ping discriminator")
        assertTrue(pongJson.contains("\"#type\":\"ws:pong\""), "WsPong JSON should carry the ws:pong discriminator")
    }

    @Test
    fun wsDeltaSerializationRoundtrip() {
        val now = 1_700_000_000_000L
        val delta: WsMessage = WsDelta(
            seq = 1L,
            sessions = listOf(Session(
                id = "s:1", createdAt = 0L, updatedAt = now, deviceOrigin = "test",
                title = "Delta Session", startTime = now
            )),
            doses = listOf(Dose(
                id = "d:1", createdAt = 0L, updatedAt = now, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "cid:1",
                routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = now
            )),
            substances = listOf(Substance(
                id = "cid:1", name = "MDMA", createdAt = 0L, updatedAt = now, deviceOrigin = "system",
                substanceClass = listOf("Empathogen"), routesOfAdministration = listOf("Oral"),
                effects = listOf("Euphoria"), dosageBands = emptyMap(),
                cachedAt = 0L, sourceVersion = "test"
            ))
        )

        // Serialize and deserialize through the polymorphic WsMessage serializer
        val json = wsJson.encodeToString(WsMessage.serializer(), delta)
        val decoded = wsJson.decodeFromString<WsMessage>(json) as WsDelta

        // Verify fields match
        assertEquals(1L, decoded.seq, "seq should match after roundtrip")
        assertEquals(1, decoded.sessions.size, "sessions count should match")
        assertEquals("Delta Session", decoded.sessions.first().title, "session title should match")
        assertEquals(1, decoded.doses.size, "doses count should match")
        assertEquals(100.0, decoded.doses.first().amount, 0.001, "dose amount should match")
        assertEquals("mg", decoded.doses.first().unit, "dose unit should match")
        assertEquals(1, decoded.substances.size, "substances count should match")
        assertEquals("MDMA", decoded.substances.first().name, "substance name should match")
        assertFalse(decoded.sessions.isEmpty(), "Delta should have non-empty session list")
        assertFalse(decoded.doses.isEmpty(), "Delta should have non-empty dose list")
        assertFalse(decoded.substances.isEmpty(), "Delta should have non-empty substance list")

        // Verify it also round-trips via the polymorphic WsMessage interface
        val asMessage = wsJson.decodeFromString<WsMessage>(json)
        assertTrue(asMessage is WsDelta, "Decoded WsDelta should be WsDelta when read as WsMessage")
        assertEquals(1L, (asMessage as WsDelta).seq, "Polymorphic roundtrip should preserve seq")
    }
}
