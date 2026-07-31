package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.sync.DeviceTrustStore.TrustedPeer
import app.journal.sync.aesEncryptionKey
import app.journal.sync.base64Encode
import app.journal.sync.encryptBody
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
            val body = resp.bodyAsText()
            assertTrue(body.contains("Test Device"))
            assertTrue(body.contains("abc123def456"))
            assertTrue(body.contains("test-device-abc123"))
        }
    }

    @Test
    fun `pairing verify returns shared secret`() {
        testApplication {
            application { installRouter() }
            val token = authenticator.generatePairingToken()
            val resp = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"test-client","clientDeviceName":"Client","clientFingerprint":"client789xyz"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("\"success\":true"))
            val secret = extractField(body, "sharedSecret")
            assertNotNull(secret)
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
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"paired-client","clientDeviceName":"Client","clientFingerprint":"client789xyz"}""")
            }
            val sharedSecret = extractField(pair.bodyAsText(), "sharedSecret")!!

            val plainBody = """{"deviceId":"paired-client","deviceName":"Client","since":0,"substances":[],"doses":[],"sessions":[],"interactions":[],"notes":[],"timelineEvents":[],"effects":[],"customUnits":[]}"""
            // Encrypt the body the same way the real client does
            val aesKey = aesEncryptionKey(sharedSecret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = authenticator.signRequest("paired-client", encryptedBody, sharedSecret)

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
            // Pair a client through the real endpoint
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"challenge-client","clientDeviceName":"Client","clientFingerprint":"cfp"}""")
            }
            assertEquals(HttpStatusCode.OK, pair.status)
            val secret = extractField(pair.bodyAsText(), "sharedSecret")!!

            val challenge = "0123456789abcdef0123456789abcdef"
            val resp = client.get("/auth/verify?deviceId=challenge-client&challenge=$challenge")
            assertEquals(HttpStatusCode.OK, resp.status)
            val data = app.journal.data.AppJson.json.decodeFromString<HostChallengeResponse>(resp.bodyAsText())
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
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    @Test
    fun `auth verify rejects missing params`() {
        testApplication {
            application { installRouter() }
            assertEquals(HttpStatusCode.BadRequest, client.get("/auth/verify").status)
        }
    }

    @Test
    fun `sync push rejects deviceId mismatch`() {
        testApplication {
            application { installRouter() }
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"real-client","clientDeviceName":"Client","clientFingerprint":"cfp"}""")
            }
            val secret = extractField(pair.bodyAsText(), "sharedSecret")!!

            // Batch claims a DIFFERENT device than the authenticated header
            val plainBody = """{"deviceId":"other-device","deviceName":"Client","since":0}"""
            val aesKey = aesEncryptionKey(secret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = authenticator.signRequest("real-client", encryptedBody, secret)
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
            val authValue = authenticator.signRequest("evil", body, "wrong-secret")
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
            onConnection = {}, deviceId = "test-device-abc123",
            deviceName = "Test Device", fingerprint = "abc123def456",
            persistAfterApply = persistAfterApply
        ).installRouting(this)
    }

    @Test
    fun `push persists applied data before responding`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"persist-client","clientDeviceName":"Client","clientFingerprint":"cfp"}""")
            }
            val secret = extractField(pair.bodyAsText(), "sharedSecret")!!

            val plainBody = """{"deviceId":"persist-client","deviceName":"Client","since":0,"sessions":[{"id":"s:p1","title":"Pushed","startTime":1000,"createdAt":1000,"updatedAt":1000,"deviceOrigin":"test"}]}"""
            val aesKey = aesEncryptionKey(secret)
            val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
            val authValue = authenticator.signRequest("persist-client", encryptedBody, secret)
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
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"ws-client","clientDeviceName":"Client","clientFingerprint":"cfp"}""")
            }
            val secret = extractField(pair.bodyAsText(), "sharedSecret")!!

            // The WS handshake auth signs the literal body "ws" (production contract)
            val authValue = authenticator.signRequest("ws-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/sync/ws?deviceId=ws-client&auth=$authValue") {
                val delta = WsDelta(
                    seq = 7L,
                    sessions = listOf(Session(
                        id = "s:ws1", title = "WSPushed", startTime = 1000L,
                        createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test"
                    ))
                )
                val encoded = wsJson.encodeToString(WsMessage.serializer(), delta)
                send(Frame.Text(encoded))
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
    fun `ws encrypted delta applies and acks like the real client`() {
        testApplication {
            var persistCount = 0
            application { installRouter(persistAfterApply = { persistCount++ }) }
            val token = authenticator.generatePairingToken()
            val pair = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","clientDeviceId":"ws-enc-client","clientDeviceName":"Client","clientFingerprint":"cfp"}""")
            }
            val secret = extractField(pair.bodyAsText(), "sharedSecret")!!

            val authValue = authenticator.signRequest("ws-enc-client", "ws", secret)
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket("/sync/ws?deviceId=ws-enc-client&auth=$authValue") {
                val delta = WsDelta(
                    seq = 9L,
                    doses = listOf(Dose(
                        id = "d:ws1", sessionId = "s:enc", substanceId = "sub:1",
                        amount = 100.0, unit = "ug", routeOfAdministration = "oral",
                        timestamp = 1000L, createdAt = 1000L, updatedAt = 1000L,
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
            suspend fun pair(deviceId: String): String {
                val token = authenticator.generatePairingToken()
                val r = client.post("/pairing/verify") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"$token","clientDeviceId":"$deviceId","clientDeviceName":"Client","clientFingerprint":"fp-$deviceId"}""")
                }
                assertEquals(HttpStatusCode.OK, r.status)
                return extractField(r.bodyAsText(), "sharedSecret")!!
            }
            val first = pair("rot-client")
            val second = pair("rot-client")
            assertNotEquals(first, second, "re-pairing must mint a fresh secret")

            suspend fun pushWith(secret: String): HttpStatusCode {
                val plainBody = """{"deviceId":"rot-client","deviceName":"Client","since":0,"substances":[],"doses":[],"sessions":[],"interactions":[],"notes":[],"timelineEvents":[],"effects":[],"customUnits":[]}"""
                val aesKey = aesEncryptionKey(secret)
                val encryptedBody = base64Encode(encryptBody(plainBody, aesKey))
                val authValue = authenticator.signRequest("rot-client", encryptedBody, secret)
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

    companion object {
        fun extractField(json: String, field: String): String? {
            val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)
        }
    }
}
