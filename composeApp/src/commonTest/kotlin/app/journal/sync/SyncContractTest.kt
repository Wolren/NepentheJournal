package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import app.journal.data.AppJson

/**
 * Contract tests for the shared sync protocol.
 *
 * These tests validate that BOTH the JVM and iOS transport produce
 * the same wire format — request bodies, auth headers, endpoint paths.
 * They run entirely on the protocol layer without needing an HTTP server.
 */
class SyncContractTest {

    private val repo = JournalRepository()
    private val json = AppJson.json

    // Deterministic test secret (32 bytes of 'K')
    private val testSecret = "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK".encodeToByteArray()
    private val testDeviceId = "test-device-001"

    private val now = 1000000L

    @Test
    fun `hmacSha256Hex produces deterministic output for known input`() {
        val data = "hello".encodeToByteArray()
        val result1 = hmacSha256Hex(testSecret, data)
        val result2 = hmacSha256Hex(testSecret, data)
        assertEquals(result1, result2, "HMAC must be deterministic for same inputs")
        assertEquals(64, result1.length, "SHA-256 hex output must be 64 chars")
    }

    @Test
    fun `hmacSha256Hex differs for different secrets`() {
        val data = "hello".encodeToByteArray()
        val secretA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".encodeToByteArray()
        val secretB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB".encodeToByteArray()
        assertNotEquals(hmacSha256Hex(secretA, data), hmacSha256Hex(secretB, data))
    }

    @Test
    fun `hmacSha256Hex differs for different data`() {
        val result1 = hmacSha256Hex(testSecret, "message one".encodeToByteArray())
        val result2 = hmacSha256Hex(testSecret, "message two".encodeToByteArray())
        assertNotEquals(result1, result2)
    }

    @Test
    fun `auth header format is timestamp-colon-nonce-colon-hex`() {
        val body = """{"test":"data"}"""
        val ts = 1234567890L
        val nonce = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
        val header = buildAuthHeader(testDeviceId, body, testSecret, ts, nonce)

        val parts = header.split(":")
        assertEquals(3, parts.size, "Auth header must have 3 colon-separated parts")
        assertEquals("1234567890", parts[0], "First part must be timestamp")
        assertEquals(nonce, parts[1], "Second part must be nonce")
        assertEquals(64, parts[2].length, "Third part must be 64-char hex signature")

        val expectedPayload = "$testDeviceId:$ts:$nonce:$body"
        val expectedSig = hmacSha256Hex(testSecret, expectedPayload.encodeToByteArray())
        assertEquals(expectedSig, parts[2])
    }

    @Test
    fun `generateNonce produces 32-char hex string`() {
        val nonce = generateNonce()
        assertEquals(32, nonce.length)
        assertTrue(nonce.all { it in "0123456789abcdef" }, "Nonce must be hex")
    }

    @Test
    fun `generateNonce produces varying output`() {
        val nonces = (1..100).map { generateNonce() }
        assertEquals(100, nonces.distinct().size, "Nonces should be unique across 100 calls")
    }

    @Test
    fun `buildSyncBatch produces null when nothing changed`() {
        val batch = buildSyncBatch(repo, testDeviceId, "TestDevice", 0L)
        assertNull(batch, "Empty repo should produce null batch")
    }

    @Test
    fun `buildSyncBatch includes changed entities after timestamp`() {
        repo.upsertSession(Session(
            id = "s1", title = "Test", createdAt = now, updatedAt = 1000L,
            deviceOrigin = "test", startTime = now
        ))

        val batch = buildSyncBatch(repo, testDeviceId, "TestDevice", 500L)
        assertNotNull(batch)
        assertEquals(1, batch!!.sessions.size)
        assertEquals("s1", batch.sessions.first().id)
        assertEquals(testDeviceId, batch.deviceId)
    }

    @Test
    fun `buildSyncBatch excludes entities older than since`() {
        repo.upsertSession(Session(
            id = "s2", title = "Old", createdAt = now, updatedAt = 100L,
            deviceOrigin = "test", startTime = now
        ))

        val batch = buildSyncBatch(repo, testDeviceId, "TestDevice", 500L)
        assertNull(batch, "Session with updatedAt=100 should be excluded since >= 500")
    }

    @Test
    fun `SyncPushRequest from batch produces valid auth header`() {
        repo.upsertSession(Session(
            id = "s3", title = "Push", createdAt = now, updatedAt = 2000L,
            deviceOrigin = "test", startTime = now
        ))

        val batch = buildSyncBatch(repo, testDeviceId, "TestDevice", 0L)
        assertNotNull(batch)

        val request = SyncPushRequest.fromBatch(batch!!, testSecret, testDeviceId)
        assertTrue(request.body.length > 0)

        // Full format check: timestamp:nonce:signature (see format test above)
        val parts = request.authHeader.split(":", limit = 3)
        assertEquals(3, parts.size)
        assertTrue(parts[0].toLongOrNull() != null, "timestamp must be numeric")
        assertEquals(32, parts[1].length, "nonce must be 32 hex chars")
        assertEquals(64, parts[2].length)

        val payload = "${request.deviceId}:${parts[0]}:${parts[1]}:${request.body}"
        val expectedSig = hmacSha256Hex(testSecret, payload.encodeToByteArray())
        assertEquals(expectedSig, parts[2])
    }

    @Test
    fun `applySyncResponse upserts all entity types`() {
        val response = SyncResponse(
            success = true,
            sessions = listOf(Session(
                id = "r1", title = "Resp", createdAt = now, updatedAt = 1000L,
                deviceOrigin = "remote", startTime = now
            )),
            doses = listOf(Dose(
                id = "d1", sessionId = "r1", substanceId = "sub1",
                routeOfAdministration = "oral",
                amount = 100.0, unit = "mg", timestamp = 1000L,
                createdAt = now, updatedAt = now, deviceOrigin = "remote"
            )),
            substances = listOf(Substance(
                id = "sub1", name = "TestSubstance",
                createdAt = now, updatedAt = now, cachedAt = now, sourceVersion = "1"
            )),
            effects = listOf(Effect(
                id = "e1", name = "Euphoria", substanceIds = listOf("sub1"),
                createdAt = now, updatedAt = now
            )),
            interactions = listOf(Interaction(
                id = "i1", substanceAId = "sub1", substanceBId = "sub2",
                riskLevel = InteractionRisk.DANGEROUS,
                createdAt = now, updatedAt = now
            ))
        )

        applySyncResponse(repo, response)

        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.doses.value.size)
        assertEquals(1, repo.substances.value.size)
        assertEquals(1, repo.effects.value.size)
        assertEquals(1, repo.interactions.value.size)
    }

    @Test
    fun `auth header with wrong secret fails verification`() {
        val request = SyncPushRequest.fromBatch(
            SyncBatch(deviceId = testDeviceId, deviceName = "T", since = 0L),
            testSecret, testDeviceId
        )

        val wrongSecret = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX".encodeToByteArray()
        val parts = request.authHeader.split(":", limit = 3)
        val payload = "${request.deviceId}:${parts[0]}:${parts[1]}:${request.body}"
        val expectedWithCorrect = hmacSha256Hex(testSecret, payload.encodeToByteArray())
        val expectedWithWrong = hmacSha256Hex(wrongSecret, payload.encodeToByteArray())

        assertEquals(parts[2], expectedWithCorrect)
        assertNotEquals(parts[2], expectedWithWrong)
    }

    @Test
    fun `endpoint paths are well-formed`() {
        assertTrue(SyncEndpoints.INFO.startsWith("/"))
        assertTrue(SyncEndpoints.PAIRING_START.startsWith("/"))
        assertTrue(SyncEndpoints.PAIRING_VERIFY.startsWith("/"))
        assertTrue(SyncEndpoints.SYNC_PUSH.startsWith("/"))
        assertTrue(SyncEndpoints.SYNC_PULL.startsWith("/"))
    }

    @Test
    fun `SyncBatch and SyncResponse are round-trip serializable`() {
        val batch = SyncBatch(
            deviceId = "d1", deviceName = "Test Phone", since = 1000L,
            sessions = listOf(Session(
                id = "s1", title = "Test", createdAt = now, updatedAt = 1000L,
                deviceOrigin = "test", startTime = now
            ))
        )
        val jsonStr = json.encodeToString(batch)
        val decoded = json.decodeFromString<SyncBatch>(jsonStr)
        assertEquals(batch.deviceId, decoded.deviceId)
        assertEquals(batch.sessions.size, decoded.sessions.size)
        assertEquals(batch.sessions.first().id, decoded.sessions.first().id)
    }

    @Test
    fun `PairingVerifyRequest is serializable`() {
        val req = PairingVerifyRequest(
            token = "ABC123", clientDeviceId = "client-1",
            clientDeviceName = "iPhone", clientFingerprint = "fp123"
        )
        val jsonStr = json.encodeToString(req)
        val decoded = json.decodeFromString<PairingVerifyRequest>(jsonStr)
        assertEquals("ABC123", decoded.token)
    }

    @Test
    fun `PairingResultResponse is serializable`() {
        val resp = PairingResultResponse(
            success = true, deviceId = "device-1",
            sharedSecret = "sec123", hostDeviceId = "host-1",
            hostDeviceName = "Desktop", hostFingerprint = "fp456"
        )
        val jsonStr = json.encodeToString(resp)
        val decoded = json.decodeFromString<PairingResultResponse>(jsonStr)
        assertTrue(decoded.success)
        assertEquals("sec123", decoded.sharedSecret)
    }

    @Test
    fun `HostInfo is serializable`() {
        val info = HostInfo("host-1", "My PC", "abc123", 2)
        val jsonStr = json.encodeToString(info)
        val decoded = json.decodeFromString<HostInfo>(jsonStr)
        assertEquals(2, decoded.protocolVersion)
    }

    @Test
    fun `empty SyncBatch round-trips without error`() {
        val batch = SyncBatch(deviceId = "d1", deviceName = "", since = 0L)
        val jsonStr = json.encodeToString(batch)
        val decoded = json.decodeFromString<SyncBatch>(jsonStr)
        assertEquals(0, decoded.sessions.size)
    }
}
