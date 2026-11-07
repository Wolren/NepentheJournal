package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.data.SEED_BUNDLED_FINGERPRINT
import app.journal.model.Dose
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Session
import app.journal.sync.DeviceTrustStore.TrustedPeer
import app.journal.sync.aesEncryptionKey
import app.journal.util.crypto.base64Decode
import app.journal.util.crypto.base64Encode
import app.journal.sync.encryptBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import java.io.File

/**
 * In-process integration tests using Ktor's testApplication (no real port binding).
 * All endpoints are exercised through the production SyncServerRouter.
 */
class KtorSyncServerIntegrationTest {

    private val testDir = File(
        System.getProperty("java.io.tmpdir"),
        "nepenthe-test-sync-${System.nanoTime()}"
    )
    private val repo = JournalRepository()
    private val trustStore = DeviceTrustStore(testDir.absolutePath)
    private val authenticator = SyncAuthenticator(trustStore)

    @BeforeTest
    fun setUp() { testDir.mkdirs() }

    @AfterTest
    fun tearDown() { testDir.deleteRecursively() }

    @Test
    fun `info returns host info`() {
        testApplication {
            application { installRouter() }
            val resp = client.get("/info")
            assertEquals(HttpStatusCode.OK, resp.status)
            // Decode the typed payload instead of raw-substring matching (audit C7): every advertised field is asserted by name.
            val info = app.journal.serde.AppJson.json.decodeFromString<HostInfo>(resp.bodyAsText())
            assertEquals("test-device-abc123", info.deviceId)
            assertEquals("Test Device", info.deviceName)
            assertEquals("abc123def456", info.fingerprint)
            assertNotNull(info.ecdhPublicKeyB64,
                "HostInfo must advertise ecdhPublicKeyB64")
            assertEquals(SYNC_PROTOCOL_VERSION, info.protocolVersion,
                "HostInfo must serve the pinned protocol version")
            assertTrue(info.wsSupported, "JVM host must advertise wsSupported=true")
        }
    }

    @Test
    fun `pairing verify returns ECDH-sealed secret only`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("test-client", "Client", "client789xyz")
            assertEquals(secret, trustStore.getSharedSecret("test-client"))
        }
    }

    @Test
    fun `pairing verify rejects invalid token`() {
        testApplication {
            application { installRouter() }
            val resp = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"bogus","clientDeviceId":"c","clientDeviceName":"","clientFingerprint":""}""")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    @Test
    fun `pairing verify rate limits after 5 attempts`() {
        testApplication {
            application { installRouter() }
            for (i in 1..5) {
                val r = client.post("/pairing/verify") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"bad-$i","clientDeviceId":"c$i","clientDeviceName":"","clientFingerprint":""}""")
                }
                assertNotEquals(HttpStatusCode.TooManyRequests, r.status)
            }
            val r = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"bad-6","clientDeviceId":"c6","clientDeviceName":"","clientFingerprint":""}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }
    }

    @Test
    fun `sync push with paired secret succeeds`() {
        testApplication {
            application { installRouter() }
            val sharedSecret = client.pairEcdh("paired-client", "Client", "client789xyz")

            val plainBody = """{"deviceId":"paired-client","deviceName":"Client","since":0,"substances":[],"doses":[],"sessions":[],"interactions":[],"notes":[],"timelineEvents":[],"effects":[],"customUnits":[]}"""
            val aesKey = aesEncryptionKey(sharedSecret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = sign("paired-client", encryptedBody, sharedSecret)

            val push = client.post("/sync/push") {
                header("X-Sync-Device", "paired-client")
                header("X-Sync-Auth", authValue)
                contentType(ContentType.Application.Json)
                setBody(encryptedBody)
            }
            assertEquals(HttpStatusCode.OK, push.status, "Push with paired secret: ${push.bodyAsText()}")
        }
    }

    @Test
    fun `sync push rejects unauthenticated`() {
        testApplication {
            application { installRouter() }
            val resp = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun `auth verify proves host knows the secret`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("challenge-client", "Client", "cfp")

            val challenge = "0123456789abcdef0123456789abcdef"
            val resp = client.get("/auth/verify?deviceId=challenge-client&challenge=$challenge")
            assertEquals(HttpStatusCode.OK, resp.status)
            val data = app.journal.serde.AppJson.json.decodeFromString<HostChallengeResponse>(resp.bodyAsText())
            assertTrue(kotlin.math.abs(System.currentTimeMillis() - data.timestamp) < 45_000)

            val expected = hmacSha256Hex(
                secret.encodeToByteArray(),
                "challenge:challenge-client:${data.timestamp}:$challenge".encodeToByteArray()
            )
            assertEquals(expected, data.signature)
        }
    }

    @Test
    fun `auth verify rejects unknown device`() {
        testApplication {
            application { installRouter() }
            val resp = client.get("/auth/verify?deviceId=unknown&challenge=abc")
            // Uniform 401: missing params, overlong challenges, and unknown devices share one code so the endpoint isn't an oracle.
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun `auth verify rejects missing params`() {
        testApplication {
            application { installRouter() }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/verify").status)
        }
    }

    @Test
    fun `sync push rejects deviceId mismatch`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("real-client", "Client", "cfp")

            val plainBody = """{"deviceId":"other-device","deviceName":"Client","since":0}"""
            val aesKey = aesEncryptionKey(secret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = sign("real-client", encryptedBody, secret)
            val push = client.post("/sync/push") {
                header("X-Sync-Device", "real-client")
                header("X-Sync-Auth", authValue)
                contentType(ContentType.Application.Json)
                setBody(encryptedBody)
            }
            assertEquals(HttpStatusCode.Forbidden, push.status)
        }
    }

    @Test
    fun `sync push rejects wrong secret`() {
        testApplication {
            application { installRouter() }
            trustStore.addPeer(TrustedPeer("evil", "Evil", "evil", "real-secret", System.currentTimeMillis()))
            val body = """{"deviceId":"evil","deviceName":"Evil","since":0}"""
            val authValue = sign("evil", body, "wrong-secret")
            val resp = client.post("/sync/push") {
                header("X-Sync-Device", "evil")
                header("X-Sync-Auth", authValue)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun `sync pull rejects unauthenticated`() {
        testApplication {
            application { installRouter() }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/pull").status)
        }
    }

    private fun Application.installRouter(persistAfterApply: (() -> Unit)? = null) {
        SyncServerRouter(
            repo = repo, trustStore = trustStore, authenticator = authenticator,
            ecdhIdentity = EcdhIdentityManager(testDir.absolutePath),
            onConnection = {}, deviceId = "test-device-abc123",
            deviceName = "Test Device", fingerprint = "abc123def456",
            persistAfterApply = persistAfterApply
        ).installRouting(this)
    }

    /**
     * Sign like the production client: the shared commonMain
     * buildAuthHeader/hmacSha256Hex pair is the single HMAC implementation
     * (SyncAuthenticator.signRequest was deleted as test-only dead code in
     * this wave; its format is identical).
     */
    private fun sign(deviceId: String, body: String, secret: String): String =
        buildAuthHeader(
            deviceId, body, secret.encodeToByteArray(),
            System.currentTimeMillis(), generateNonce()
        )

    /**
     * Pair through the contract-g ECDH flow against the production router:
     * fetch the host's static P-256 key from HostInfo, send an EPHEMERAL
     * public key, and resolve the secret ONLY by unwrapping ecdhSecretB64.
     * Proves the legacy plaintext/PBKDF2 fields carry no secret by decoding
     * the response STRUCTURALLY (Json object key presence), so the
     * assertion still fails if those keys ever come back even though the
     * DTO no longer declares them. No raw substring extraction remains
     * (the old extractField regex helper is gone).
     */
    private suspend fun HttpClient.pairEcdh(
        clientDeviceId: String,
        clientDeviceName: String = "Client",
        clientFingerprint: String = "cfp"
    ): String {
        val infoBody = get("/pairing/start").bodyAsText()
        val hostKey = app.journal.serde.AppJson.json
            .decodeFromString<HostInfo>(infoBody).ecdhPublicKeyB64
            ?: error("HostInfo must advertise ecdhPublicKeyB64: $infoBody")
        val ephemeral = EcdhIdentityManager.generateEphemeralKeyPair()
        val myKey = EcdhIdentityManager.uncompressedPointB64(ephemeral.public)
        val token = authenticator.generatePairingToken()
        val resp = post("/pairing/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"token":"$token","clientDeviceId":"$clientDeviceId",""" +
                    """"clientDeviceName":"$clientDeviceName",""" +
                    """"clientFingerprint":"$clientFingerprint",""" +
                    """"clientEcdhPublicKeyB64":"$myKey"}"""
            )
        }
        val body = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status, "ECDH pairing must succeed: $body")
        val result = app.journal.serde.AppJson.json.decodeFromString<PairingResultResponse>(body)
        val rawFields = app.journal.serde.AppJson.json.parseToJsonElement(body).jsonObject
        assertFalse("sharedSecret" in rawFields,
            "legacy plaintext sharedSecret must no longer be emitted")
        assertFalse("encSecretB64" in rawFields,
            "legacy PBKDF2 encSecretB64 must no longer be emitted")
        val sealed = result.ecdhSecretB64
            ?: error("server must seal the secret in ecdhSecretB64: $body")
        val shared = EcdhIdentityManager.agree(ephemeral.private, hostKey)
        val secret = try {
            PairingEcdh.unwrapSharedSecret(shared, sealed)
        } finally {
            shared.fill(0)
        }
        assertFalse(body.contains(secret), "the sealed secret must never appear in the clear")
        return secret
    }

    @Test
    fun `push persists applied data before responding`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("persist-client", "Client", "cfp")

            val plainBody = """{"deviceId":"persist-client","deviceName":"Client","since":0,"sessions":[{"id":"s:p1","title":"Pushed","startTime":1700000000000,"createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test"}]}"""
            val aesKey = aesEncryptionKey(secret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = sign("persist-client", encryptedBody, secret)
            val push = client.post("/sync/push") {
                header("X-Sync-Device", "persist-client")
                header("X-Sync-Auth", authValue)
                contentType(ContentType.Application.Json)
                setBody(encryptedBody)
            }
            assertEquals(HttpStatusCode.OK, push.status)
            assertEquals(1, persistCount, "persistAfterApply must fire on every accepted push")
        }
    }

    @Test
    fun `interim push slices defer the persist and the pull page to the final slice`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("chunk-client", "Client", "cfp")
            val aesKey = aesEncryptionKey(secret)

            suspend fun push(index: Int, phase: String?): SyncResponse {
                val plainBody = """{"deviceId":"chunk-client","deviceName":"Client","since":0,""" +
                    """"sessions":[{"id":"s:chunk-$index","title":"Chunk","startTime":1700000000000,""" +
                    """"createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test"}]}"""
                val encrypted = base64Encode(encryptBody(plainBody, aesKey))
                val resp = client.post("/sync/push") {
                    header("X-Sync-Device", "chunk-client")
                    header("X-Sync-Auth", sign("chunk-client", encrypted, secret))
                    if (phase != null) header(SyncEndpoints.PUSH_PHASE_HEADER, phase)
                    contentType(ContentType.Application.Json)
                    setBody(encrypted)
                }
                assertEquals(HttpStatusCode.OK, resp.status)
                val decrypted = decryptBody(base64Decode(resp.bodyAsText()), aesKey)
                return app.journal.serde.AppJson.json.decodeFromString<SyncResponse>(decrypted)
            }

            val first = push(1, SyncEndpoints.PUSH_PHASE_INTERIM)
            val second = push(2, SyncEndpoints.PUSH_PHASE_INTERIM)
            assertEquals(0, persistCount, "interim slices must not write the journal")
            assertTrue(first.sessions.isEmpty() && first.substances.isEmpty(),
                "interim slice must answer with an empty pull page")
            assertTrue(second.sessions.isEmpty(),
                "every interim slice must answer with an empty pull page")

            val final = push(3, SyncEndpoints.PUSH_PHASE_FINAL)
            assertEquals(1, persistCount, "the final slice persists the whole cycle exactly once")
            assertTrue(final.sessions.isNotEmpty(), "the final slice carries the pull page")
        }
    }

    @Test
    fun `pushes without the phase header keep persisting per slice`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("legacy-client", "Legacy", "lfp")
            val aesKey = aesEncryptionKey(secret)

            suspend fun push(index: Int) {
                val plainBody = """{"deviceId":"legacy-client","deviceName":"Legacy","since":0,""" +
                    """"sessions":[{"id":"s:legacy-$index","title":"Legacy","startTime":1700000000000,""" +
                    """"createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test"}]}"""
                val encrypted = base64Encode(encryptBody(plainBody, aesKey))
                val resp = client.post("/sync/push") {
                    header("X-Sync-Device", "legacy-client")
                    header("X-Sync-Auth", sign("legacy-client", encrypted, secret))
                    contentType(ContentType.Application.Json)
                    setBody(encrypted)
                }
                assertEquals(HttpStatusCode.OK, resp.status)
            }

            push(1)
            push(2)
            assertEquals(2, persistCount, "a client without the phase header keeps a persist per request")
        }
    }

    @Test
    fun `ws delta applies data and persists before ack`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("ws-client", "Client", "cfp")

            val authValue = sign("ws-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "ws-client")
                header(SyncAuthenticator.AUTH_HEADER, authValue)
            }) {
                val delta = WsDelta(
                    seq = 7L,
                    sessions = listOf(Session(
                        id = "s:ws1", title = "WSPushed", startTime = 1_700_000_000_000L,
                        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test"
                    ))
                )
                // Keyed connections only accept encrypted deltas: encode, AES-GCM encrypt, then base64, same as the real client.
                val encoded = wsJson.encodeToString(WsMessage.serializer(), delta)
                val wsAesKey = aesEncryptionKey(secret)
                send(Frame.Text(base64Encode(encryptBody(encoded, wsAesKey))))
                val ackFrame = incoming.receive() as Frame.Text
                val ack = wsJson.decodeFromString<WsMessage>(ackFrame.readText()) as WsAck
                assertEquals(7L, ack.seq, "ack must echo the delta seq")
                assertNull(ack.error, "delta should be accepted")
                assertTrue(repo.sessions.value.any { it.id == "s:ws1" },
                    "delta must be applied to the repo before the ack")
                assertEquals(1, persistCount,
                    "persistAfterApply must fire before the ack is sent")
            }
        }
    }

    @Test
    fun `ws plaintext delta refused on keyed connection`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("ws-plain-client", "Client", "cfp")

            val authValue = sign("ws-plain-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "ws-plain-client")
                header(SyncAuthenticator.AUTH_HEADER, authValue)
            }) {
                val delta = WsDelta(
                    seq = 11L,
                    sessions = listOf(Session(
                        id = "s:ws2", title = "Plaintext", startTime = 1700000000000L,
                        createdAt = 1700000000000L, updatedAt = 1700000000000L, deviceOrigin = "test"
                    ))
                )
                send(Frame.Text(wsJson.encodeToString(WsMessage.serializer(), delta)))
                val ackFrame = incoming.receive() as Frame.Text
                val ack = wsJson.decodeFromString<WsMessage>(ackFrame.readText()) as WsAck
                assertNotNull(ack.error, "plaintext delta on a keyed connection must be refused")
                assertTrue(repo.sessions.value.none { it.id == "s:ws2" },
                    "refused delta must not reach the repo")
            }
        }
    }

    @Test
    fun `ws encrypted delta applies and acks like the real client`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("ws-enc-client", "Client", "cfp")

            val authValue = sign("ws-enc-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "ws-enc-client")
                header(SyncAuthenticator.AUTH_HEADER, authValue)
            }) {
                val delta = WsDelta(
                    seq = 9L,
                    doses = listOf(Dose(
                        id = "d:ws1", sessionId = "s:enc", substanceId = "sub:1",
                        amount = 100.0, unit = "ug", routeOfAdministration = "oral",
                        timestamp = 1_700_000_000_000L, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                        deviceOrigin = "test"
                    ))
                )
                // Real client path: polymorphic encode, then AES-GCM encrypt, then base64.
                val plaintext = wsJson.encodeToString(WsMessage.serializer(), delta)
                val aesKey = aesEncryptionKey(secret)
                val encrypted = base64Encode(encryptBody(plaintext, aesKey))
                send(Frame.Text(encrypted))
                val ackFrame = incoming.receive() as Frame.Text
                val ack = wsJson.decodeFromString<WsMessage>(ackFrame.readText()) as WsAck
                assertEquals(9L, ack.seq, "ack must echo the delta seq")
                assertNull(ack.error, "encrypted delta should be accepted")
                assertTrue(repo.doses.value.any { it.id == "d:ws1" },
                    "encrypted delta must be applied to the repo")
                assertEquals(1, persistCount,
                    "persistAfterApply must fire for the encrypted path too")
            }
        }
    }

    @Test
    fun `repairing rotates secret and old secret rejected`() {
        testApplication {
            application { installRouter() }
            suspend fun pair(deviceId: String): String =
                client.pairEcdh(deviceId, "Client", "fp-$deviceId")
            val first = pair("rot-client")
            val second = pair("rot-client")
            assertNotEquals(first, second, "re-pairing must mint a fresh secret")

            suspend fun pushWith(secret: String): HttpStatusCode {
                val plainBody = """{"deviceId":"rot-client","deviceName":"Client","since":0,"substances":[],"doses":[],"sessions":[],"interactions":[],"notes":[],"timelineEvents":[],"effects":[],"customUnits":[]}"""
                val aesKey = aesEncryptionKey(secret)
                val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
                val authValue = sign("rot-client", encryptedBody, secret)
                return client.post("/sync/push") {
                    header("X-Sync-Device", "rot-client")
                    header("X-Sync-Auth", authValue)
                    contentType(ContentType.Application.Json)
                    setBody(encryptedBody)
                }.status
            }
            assertEquals(HttpStatusCode.Unauthorized, pushWith(first),
                "stale secret must be rejected after rotation")
            assertEquals(HttpStatusCode.OK, pushWith(second),
                "fresh secret must still authenticate")
        }
    }

    @Test
    fun `pairing rate limit ignores X-Forwarded-For`() {
        testApplication {
            application { installRouter() }
            for (i in 1..5) {
                val r = client.post("/pairing/verify") {
                    header("X-Forwarded-For", "9.9.9.9")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"bad-$i","clientDeviceId":"c$i","clientDeviceName":"","clientFingerprint":""}""")
                }
                assertNotEquals(HttpStatusCode.TooManyRequests, r.status)
            }
            // X-Forwarded-For is client-controlled on direct LAN connections and must never act as the rate-limit bucket key (audit H1).
            val r = client.post("/pairing/verify") {
                header("X-Forwarded-For", "9.9.9.9")
                contentType(ContentType.Application.Json)
                setBody("""{"token":"bad-6","clientDeviceId":"c6","clientDeviceName":"","clientFingerprint":""}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }
    }

    /**
     * Contract section d TEST obligation (audit C1): a first sync against
     * the bundled seed shape (2015 interactions, cap 100) must go out as
     * validator-sized slices, sent sequentially, and the cursor may advance
     * to cycleStart ONLY when every slice acked success=true.
     */
    @Test
    fun `2015 interaction push advances cursor only after all slices ack`() = runBlocking {
        var persistCount = 0
        val server = KtorSyncServer(
            repo = repo,
            port = 0, // ephemeral: never collide with concurrently running sibling test JVMs
            tlsIdentity = TlsIdentityManager(testDir.absolutePath),
            ecdhIdentity = EcdhIdentityManager(testDir.absolutePath),
            trustStore = trustStore,
            authenticator = authenticator,
            onConnection = {},
            persistAfterApply = { persistCount++ }
        )
        val info = server.start()
        val port = info.port
        try {
            // Sender side: the 2015-interaction seed that deadlocked C1.
            val clientRepo = JournalRepository()
            val base = 1_700_000_000_000L
            repeat(2015) { i ->
                clientRepo.upsertInteraction(Interaction(
                    id = "int:$i",
                    createdAt = base + i * 1000L,
                    updatedAt = base + i * 1000L,
                    substanceAId = "sub:a",
                    substanceBId = "sub:b",
                    riskLevel = InteractionRisk.LOW,
                    description = "seed interaction $i"
                ))
            }

            val bootstrap = KtorSyncClient(
                repo = clientRepo, deviceId = "chunk-client", deviceName = "Chunker"
            )
            val hostInfo = bootstrap.requestHostInfo("127.0.0.1", port).getOrThrow()
            assertEquals(SYNC_PROTOCOL_VERSION, hostInfo.protocolVersion,
                "both hosts must serve the pinned protocol version")
            assertNotNull(hostInfo.ecdhPublicKeyB64)
            val token = authenticator.generatePairingToken()
            val pairing = bootstrap.completePairing(
                host = "127.0.0.1", port = port, token = token,
                clientDeviceId = "chunk-client", clientDeviceName = "Chunker",
                clientFingerprint = "chunk-fp",
                hostEcdhPublicKeyB64 = hostInfo.ecdhPublicKeyB64
            )
            bootstrap.close()
            val secret = pairing.getOrThrow().sharedSecret

            val client = KtorSyncClient(
                repo = clientRepo, deviceId = "chunk-client",
                deviceFingerprint = "chunk-fp", deviceName = "Chunker",
                sharedSecret = secret
            )
            try {
                val push = client.pushChanges(
                    host = "127.0.0.1", port = port,
                    deviceId = "chunk-client", deviceName = "Chunker", since = 0L
                )
                assertTrue(push.isSuccess, "chunked push must succeed: ${push.exceptionOrNull()?.message}")
                val result = push.getOrThrow()

                val expectedSlices =
                    (2015 + SyncLimits.MAX_INTERACTIONS - 1) / SyncLimits.MAX_INTERACTIONS
                assertEquals(expectedSlices, result.acks.size,
                    "2015 interactions must be sliced into validator-sized pushes")
                assertTrue(result.acks.all { it.success }, "every slice must ack success=true")
                assertEquals(2015, repo.interactions.value.size,
                    "every slice must be applied to the server repo")
                assertEquals(1, persistCount,
                    "a chunked cycle must write the host journal once, not once per slice")

                val previous = 1_700_000_000_000L
                assertEquals(result.cycleStart,
                    advanceCursorIfAllSucceeded(previous, result.cycleStart, result.acks),
                    "cursor advances to cycleStart only after ALL slices ack success")
                val withOneFailure = result.acks.dropLast(1) +
                    result.acks.last().copy(success = false)
                assertEquals(previous,
                    advanceCursorIfAllSucceeded(previous, result.cycleStart, withOneFailure),
                    "one failed slice must hold the cursor at its previous value")
                assertEquals(previous,
                    advanceCursorIfAllSucceeded(previous, result.cycleStart, emptyList()),
                    "an empty ack list must never advance the cursor")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    /**
     * Contract section a TEST obligation: every authenticated WS handshake is
     * a NEW SEQUENCE EPOCH (KtorSyncServerJvm discards the device's prior
     * wsSeqState at handshake). Two properties, both proven through the
     * production router:
     *  1. within one connection, a replayed seq is rejected with a WsAck
     *     error while the next fresh seq still succeeds (rejection does not
     *     desync the window), and
     *  2. a brand-new connection for the SAME device restarts at seq 1:
     *     without the handshake reset, that frame would be rejected as
     *     "Stale or replayed seq" and the client would be stuck forever.
     */
    @Test
    fun `ws handshake starts a fresh seq epoch per connection`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("epoch-client", "Client", "cfp")
            val aesKey = aesEncryptionKey(secret)

            // Connection 1: accept seq 1, reject its replay, accept seq 2.
            val authValue = sign("epoch-client", "ws", secret)
            createClient { install(WebSockets) }.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "epoch-client")
                header(SyncAuthenticator.AUTH_HEADER, authValue)
            }) {
                suspend fun sendAndAck(delta: WsDelta): WsAck {
                    val encoded = wsJson.encodeToString(WsMessage.serializer(), delta)
                    send(Frame.Text(base64Encode(encryptBody(encoded, aesKey))))
                    val frame = incoming.receive() as Frame.Text
                    return wsJson.decodeFromString<WsMessage>(frame.readText()) as WsAck
                }
                fun delta(seq: Long, title: String) = WsDelta(
                    seq = seq,
                    sessions = listOf(Session(
                        id = "s:epoch-$seq", title = title, startTime = 1_700_000_000_000L,
                        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                        deviceOrigin = "test"
                    ))
                )
                val first = sendAndAck(delta(1L, "Epoch one"))
                assertEquals(1L, first.seq); assertNull(first.error, "seq 1 must be accepted")
                val replay = sendAndAck(delta(1L, "Epoch replay"))
                assertNotNull(replay.error, "replayed seq must be rejected")
                assertTrue(replay.error!!.contains("replayed"), "got: ${replay.error}")
                val next = sendAndAck(delta(2L, "Epoch two"))
                assertNull(next.error,
                    "a rejection must not desync the window: fresh seq 2 still accepted")
            }

            // Connection 2: same device, fresh handshake, seq 1 again.
            createClient { install(WebSockets) }.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "epoch-client")
                header(SyncAuthenticator.AUTH_HEADER, sign("epoch-client", "ws", secret))
            }) {
                val encoded = wsJson.encodeToString(WsMessage.serializer(), WsDelta(
                    seq = 1L,
                    sessions = listOf(Session(
                        id = "s:epoch-new", title = "Second connection",
                        startTime = 1_700_000_000_000L, createdAt = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L, deviceOrigin = "test"
                    ))
                ))
                send(Frame.Text(base64Encode(encryptBody(encoded, aesKey))))
                val frame = incoming.receive() as Frame.Text
                val ack = wsJson.decodeFromString<WsMessage>(frame.readText()) as WsAck
                assertNull(ack.error,
                    "the handshake must reset seq state so seq 1 of a NEW " +
                        "connection is accepted; got error: ${ack.error}")
            }

            val epochIds = repo.sessions.value.map { it.id }
                .filter { it.startsWith("s:epoch-") }.toSet()
            assertEquals(setOf("s:epoch-1", "s:epoch-2", "s:epoch-new"), epochIds,
                "replayed delta must never double-apply; both epochs must apply")
        }
    }

    /**
     * Contract section b TEST obligation: the tombstone cutoff is the
     * SENDER's cursor on every JVM apply path. The same fixture (one local
     * entity above the cursor, one at-or-below, one absent) must produce
     * identical keep/delete outcomes through HTTP push and through WS apply.
     */
    @Test
    fun `tombstone cutoff parity between HTTP push and WS apply`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("parity-client", "Client", "cfp")
            val cursor = 1_700_000_000_000L

            fun seed(tag: String) {
                repo.upsertSession(Session(
                    id = "s:$tag:new", title = "Newer $tag", startTime = cursor,
                    createdAt = cursor, updatedAt = cursor + 5_000, deviceOrigin = "local"
                ))
                repo.upsertSession(Session(
                    id = "s:$tag:old", title = "Older $tag", startTime = cursor - 10_000,
                    createdAt = cursor - 10_000, updatedAt = cursor - 5_000, deviceOrigin = "local"
                ))
            }

            fun outcome(tag: String): Triple<Boolean, Boolean, Boolean> {
                val deleted = repo.deletedIdsSince(0L).deletedSessionIds
                return Triple(
                    repo.getSession("s:$tag:new") != null,
                    repo.getSession("s:$tag:old") == null,
                    deleted.contains("s:$tag:ghost")
                )
            }

            fun assertRule(tag: String, path: String) {
                val (newerKept, olderDeleted, _) = outcome(tag)
                assertTrue(newerKept,
                    "$path: local.updatedAt above the sender cursor must survive the delete")
                assertTrue(olderDeleted,
                    "$path: local.updatedAt <= sender cursor must be deleted")
                assertNull(repo.getSession("s:$tag:ghost"),
                    "$path: a tombstone for an absent local entity leaves it absent")
            }

            fun fixtureBody() =
                """{"deviceId":"parity-client","deviceName":"Client","since":$cursor,""" +
                    """"sessions":[],"doses":[],"substances":[],"interactions":[],"notes":[],""" +
                    """"timelineEvents":[],"effects":[],"customUnits":[],""" +
                    """"deletedSessionIds":["s:%s:new","s:%s:old","s:%s:ghost"]}"""

            // Path 1: HTTP push
            seed("http")
            val aesKey = aesEncryptionKey(secret)
            val batch = fixtureBody().let { String.format(it, "http", "http", "http") }
            val encryptedBody = base64Encode(encryptBody(batch, aesKey))
            val push = client.post("/sync/push") {
                header("X-Sync-Device", "parity-client")
                header("X-Sync-Auth", sign("parity-client", encryptedBody, secret))
                contentType(ContentType.Application.Json)
                setBody(encryptedBody)
            }
            assertEquals(HttpStatusCode.OK, push.status, push.bodyAsText())
            assertRule("http", "HTTP push")

            // Path 2: WS delta with the same cursor and tombstone list
            seed("ws")
            val authValue = sign("parity-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }
            wsClient.webSocket("/sync/ws", {
                header(SyncAuthenticator.DEVICE_ID_HEADER, "parity-client")
                header(SyncAuthenticator.AUTH_HEADER, authValue)
            }) {
                val delta = WsDelta(
                    seq = 1L,
                    since = cursor,
                    deletedSessionIds = listOf("s:ws:new", "s:ws:old", "s:ws:ghost")
                )
                val encoded = wsJson.encodeToString(WsMessage.serializer(), delta)
                send(Frame.Text(base64Encode(encryptBody(encoded, aesKey))))
                val ackFrame = incoming.receive() as Frame.Text
                val ack = wsJson.decodeFromString<WsMessage>(ackFrame.readText()) as WsAck
                assertNull(ack.error, "parity delta must be accepted: ${ack.error}")
            }
            assertRule("ws", "WS apply")

            assertEquals(outcome("http"), outcome("ws"),
                "HTTP push and WS apply must produce identical tombstone-cutoff outcomes")
        }
    }

    /**
     * Contract section c, JVM HTTP leg of the shared mergeSessionConflict
     * extraction: the push path must route sessions through the ONE
     * commonMain function (both orderings pinned by
     * commonTest SyncSessionConflictMergeTest; the iOS host calls the same
     * function and is reading-verified only, since iosMain cannot compile
     * on this host). Fixture: the LOCAL session is newer, so the incoming
     * session loses, both outcomes survive (local session intact plus a
     * conflict note), provenance is `sync:<peer>`, and an identical replay
     * changes nothing (idempotent, deterministic conflict-note id).
     */
    @Test
    fun `HTTP push session conflict routes through shared mergeSessionConflict`() {
        testApplication {
            application { installRouter() }
            val secret = client.pairEcdh("conflict-client", "Client", "cfp")

            repo.upsertSession(Session(
                id = "s:conflict", title = "Local title", startTime = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_050_000L,
                deviceOrigin = "local", outcome = "local outcome"
            ))
            val plainBody =
                """{"deviceId":"conflict-client","deviceName":"Client","since":1700000000000,""" +
                    """"sessions":[{"id":"s:conflict","title":"Remote title","startTime":1700000000000,""" +
                    """"createdAt":1700000000000,"updatedAt":1700000001000,"deviceOrigin":"",""" +
                    """"outcome":"remote outcome"}]}"""

            suspend fun pushBatch(): HttpStatusCode {
                val aesKey = aesEncryptionKey(secret)
                val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
                val authValue = sign("conflict-client", encryptedBody, secret)
                return client.post("/sync/push") {
                    header("X-Sync-Device", "conflict-client")
                    header("X-Sync-Auth", authValue)
                    contentType(ContentType.Application.Json)
                    setBody(encryptedBody)
                }.status
            }

            assertEquals(HttpStatusCode.OK, pushBatch(), "conflicting push must be accepted")

            val local = repo.getSession("s:conflict")
            assertNotNull(local, "the newer local session must never be clobbered")
            assertEquals(1_700_000_050_000L, local.updatedAt, "local updatedAt must not move")
            assertEquals("local outcome", local.outcome, "local outcome must be preserved")

            val conflict = repo.notes.value.single { it.id == "conflict:s:conflict:conflict-client" }
            assertEquals("s:conflict", conflict.sessionId)
            assertEquals("Sync conflict: Remote title", conflict.title)
            assertEquals("Remote: remote outcome\n\nLocal: local outcome", conflict.body)
            assertEquals("sync:conflict-client", conflict.deviceOrigin)
            assertEquals(1_700_000_050_000L, conflict.updatedAt,
                "the conflict note is stamped with the newer of the two timestamps")
            assertTrue(conflict.conflictSiblings.isEmpty(),
                "session conflicts are standalone notes, not sibling-carrying note merges")
            // Contract c5: pendingConflictCount counts sibling-carrying note merges only
            assertEquals(0, runBlocking { repo.pendingConflictCount.first() })

            assertEquals(HttpStatusCode.OK, pushBatch(), "replayed push must still be accepted")
            assertEquals(1, repo.notes.value.count { it.id == "conflict:s:conflict:conflict-client" },
                "replaying the batch must never duplicate the conflict note")
            assertEquals("local outcome", repo.getSession("s:conflict")?.outcome)
        }
    }

    /**
     * Regression for the pull-pagination tie bug, end to end over a REAL
     * server: the bundled seed shape (2015 interactions sharing ONE
     * updatedAt) must arrive in FULL when a fresh client pulls from cursor 0.
     * The old timestamp-only cursor cut at 100 rows, then filtered
     * `updatedAt > since` and matched the zero remaining rows of the tie
     * group — it reported the drain as finished with 100 of 2015 delivered.
     */
    @Test
    fun `pull from cursor zero delivers every tied-timestamp interaction`() = runBlocking {
        val server = KtorSyncServer(
            repo = repo,
            port = 0, // ephemeral: never collide with concurrently running sibling test JVMs
            tlsIdentity = TlsIdentityManager(testDir.absolutePath),
            ecdhIdentity = EcdhIdentityManager(testDir.absolutePath),
            trustStore = trustStore,
            authenticator = authenticator,
            onConnection = {}
        )
        val info = server.start()
        val port = info.port
        val tied = 1_783_942_757_808L
        try {
            repeat(2015) { i ->
                // deviceOrigin "host": a system origin would classify these as bundled rows and the client now skips those.
                repo.upsertInteraction(Interaction(
                    id = "int:$i",
                    createdAt = tied,
                    updatedAt = tied,
                    deviceOrigin = "host",
                    substanceAId = "sub:a",
                    substanceBId = "sub:b",
                    riskLevel = InteractionRisk.LOW,
                    description = "seed interaction $i"
                ))
            }

            val clientRepo = JournalRepository()
            val bootstrap = KtorSyncClient(
                repo = clientRepo, deviceId = "tie-client", deviceName = "Tie"
            )
            val hostInfo = bootstrap.requestHostInfo("127.0.0.1", port).getOrThrow()
            assertNotNull(hostInfo.ecdhPublicKeyB64)
            val token = authenticator.generatePairingToken()
            val pairing = bootstrap.completePairing(
                host = "127.0.0.1", port = port, token = token,
                clientDeviceId = "tie-client", clientDeviceName = "Tie",
                clientFingerprint = "tie-fp",
                hostEcdhPublicKeyB64 = hostInfo.ecdhPublicKeyB64
            )
            bootstrap.close()
            val secret = pairing.getOrThrow().sharedSecret

            val client = KtorSyncClient(
                repo = clientRepo, deviceId = "tie-client",
                deviceFingerprint = "tie-fp", deviceName = "Tie",
                sharedSecret = secret
            )
            try {
                val push = client.pushChanges(
                    host = "127.0.0.1", port = port,
                    deviceId = "tie-client", deviceName = "Tie", since = 0L
                )
                assertTrue(push.isSuccess,
                    "empty push + full pull must succeed: ${push.exceptionOrNull()?.message}")
                val result = push.getOrThrow()
                assertTrue(result.drainComplete,
                    "the pull drain must run all the way to an untruncated page")
                assertEquals(2015, clientRepo.interactions.value.size,
                    "every tied-timestamp interaction must cross the pull (the old cursor stopped at 100)")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }
    /**
     * End-to-end proof of the bundled-library shortcut over a real socket: both
     * ends share the seed fingerprint, so no deviceOrigin "system" row may cross
     * either direction while user rows still flow both ways.
     */
    @Test
    fun `bundled rows stay home when both ends share the fingerprint`() = runBlocking {
        val server = KtorSyncServer(
            repo = repo,
            port = 0,
            tlsIdentity = TlsIdentityManager(testDir.absolutePath),
            ecdhIdentity = EcdhIdentityManager(testDir.absolutePath),
            trustStore = trustStore,
            authenticator = authenticator,
            onConnection = {}
        )
        val info = server.start()
        val port = info.port
        try {
            repo.upsertInteraction(Interaction(
                id = "int:host-bundled", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                substanceAId = "sub:a", substanceBId = "sub:b",
                riskLevel = InteractionRisk.LOW, description = "host bundled row"
            ))
            repo.upsertSession(Session(
                id = "s:from-desktop", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                deviceOrigin = "desktop", title = "FROM-DESKTOP", startTime = 1_700_000_000_000L
            ))

            val clientRepo = JournalRepository()
            repeat(300) { i ->
                clientRepo.upsertInteraction(Interaction(
                    id = "int:client-bundled-$i",
                    createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                    substanceAId = "sub:a", substanceBId = "sub:b",
                    riskLevel = InteractionRisk.LOW, description = "phone bundled row $i"
                ))
            }
            clientRepo.upsertSession(Session(
                id = "s:from-phone", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                deviceOrigin = "phone", title = "FROM-PHONE", startTime = 1_700_000_000_000L
            ))

            val bootstrap = KtorSyncClient(repo = clientRepo, deviceId = "skip-client", deviceName = "Skip")
            val hostInfo = bootstrap.requestHostInfo("127.0.0.1", port).getOrThrow()
            assertEquals(SEED_BUNDLED_FINGERPRINT, hostInfo.seedFingerprint,
                "the host must advertise its bundled-library fingerprint")
            val token = authenticator.generatePairingToken()
            val pairing = bootstrap.completePairing(
                host = "127.0.0.1", port = port, token = token,
                clientDeviceId = "skip-client", clientDeviceName = "Skip",
                clientFingerprint = "skip-fp",
                hostEcdhPublicKeyB64 = hostInfo.ecdhPublicKeyB64
            )
            bootstrap.close()
            val client = KtorSyncClient(
                repo = clientRepo, deviceId = "skip-client",
                deviceFingerprint = "skip-fp", deviceName = "Skip",
                sharedSecret = pairing.getOrThrow().sharedSecret
            )
            try {
                val push = client.pushChanges(
                    host = "127.0.0.1", port = port,
                    deviceId = "skip-client", deviceName = "Skip", since = 0L,
                    skipBundled = hostInfo.seedFingerprint == SEED_BUNDLED_FINGERPRINT
                )
                assertTrue(push.isSuccess, "push must succeed: ${push.exceptionOrNull()?.message}")
                assertTrue(repo.interactions.value.none { it.id.startsWith("int:client-bundled-") },
                    "no bundled row may be pushed to a peer that ships the same library")
                assertTrue(repo.sessions.value.any { it.title == "FROM-PHONE" },
                    "user sessions must still cross the push")
                assertTrue(clientRepo.sessions.value.any { it.title == "FROM-DESKTOP" },
                    "user sessions must still cross the pull")
                assertTrue(clientRepo.interactions.value.none { it.id == "int:host-bundled" },
                    "no bundled row may be pulled from a peer that ships the same library")
                assertEquals(300, clientRepo.interactions.value.count { it.id.startsWith("int:client-bundled-") },
                    "the local bundled rows must stay intact")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }
}