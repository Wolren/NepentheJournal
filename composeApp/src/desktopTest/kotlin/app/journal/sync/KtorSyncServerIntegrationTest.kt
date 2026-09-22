package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Session
import app.journal.sync.DeviceTrustStore.TrustedPeer
import app.journal.sync.aesEncryptionKey
import app.journal.util.crypto.base64Encode
import app.journal.sync.encryptBody
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
            // Decode the typed payload instead of raw-substring matching
            // (audit C7): every advertised field is asserted by name.
            val info = app.journal.serde.AppJson.json.decodeFromString<HostInfo>(resp.bodyAsText())
            assertEquals("test-device-abc123", info.deviceId)
            assertEquals("Test Device", info.deviceName)
            assertEquals("abc123def456", info.fingerprint)
            // Contract g/f: both HostInfo sites advertise the static ECDH
            // public point, serve the pinned protocol version, and keep WS.
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
            // The host stored the very secret it sealed under the ECDH key.
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
            // Encrypt the body the same way the real client does
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
            // Pair a client through the real ECDH endpoint
            val secret = client.pairEcdh("challenge-client", "Client", "cfp")

            val challenge = "0123456789abcdef0123456789abcdef"
            val resp = client.get("/auth/verify?deviceId=challenge-client&challenge=$challenge")
            assertEquals(HttpStatusCode.OK, resp.status)
            val data = app.journal.serde.AppJson.json.decodeFromString<HostChallengeResponse>(resp.bodyAsText())
            assertTrue(kotlin.math.abs(System.currentTimeMillis() - data.timestamp) < 45_000)

            // Recompute the expected signature with the shared secret
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
            // Uniform 401: missing params, overlong challenges, and unknown
            // devices share one code so the endpoint is not an oracle.
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

            // Batch claims a DIFFERENT device than the authenticated header
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
     * Also proves the legacy plaintext/PBKDF2 fields carry no secret.
     */
    private suspend fun HttpClient.pairEcdh(
        clientDeviceId: String,
        clientDeviceName: String = "Client",
        clientFingerprint: String = "cfp"
    ): String {
        val infoBody = get("/pairing/start").bodyAsText()
        val hostKey = extractField(infoBody, "ecdhPublicKeyB64")
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
        assertNull(extractField(body, "sharedSecret"),
            "legacy plaintext sharedSecret must no longer be emitted")
        assertNull(extractField(body, "encSecretB64"),
            "legacy PBKDF2 encSecretB64 must no longer be emitted")
        val sealed = extractField(body, "ecdhSecretB64")
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
            // The durability callback must have run before the ack returned
            assertEquals(1, persistCount, "persistAfterApply must fire on every accepted push")
        }
    }

    @Test
    fun `ws delta applies data and persists before ack`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val secret = client.pairEcdh("ws-client", "Client", "cfp")

            // The WS handshake auth signs the literal body "ws" (production contract)
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
                // Keyed connections only accept encrypted deltas: encode,
                // AES-GCM encrypt, then base64, same as the real client.
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
                // Real client path: polymorphic encode, then AES-GCM encrypt,
                // then base64. The server must decrypt and apply it.
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
            // Same client with a spoofed XFF header: must STILL be rate limited.
            // X-Forwarded-For is client-controlled on direct LAN connections and
            // must never act as the rate-limit bucket key (audit H1).
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

            // Real contract-g ECDH pairing against the real server.
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

                // The pinned cursor rule over the REAL ack list of this push:
                // advance to cycleStart only with a complete success set.
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

            // Both epochs' payloads landed exactly once each.
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

            // Path 2: WS delta with the SAME cursor and tombstone list
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

            // Cross-path parity: identical outcomes on both transports.
            assertEquals(outcome("http"), outcome("ws"),
                "HTTP push and WS apply must produce identical tombstone-cutoff outcomes")
        }
    }

    companion object {
        fun extractField(json: String, field: String): String? {
            val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)
        }
    }
}
