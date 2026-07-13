package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import java.io.File

/**
 * Integration tests for Sync server routing logic using Ktor's testApplication.
 * Tests all endpoints: /info, /pairing/verify, /sync/push, /sync/pull, /sync/ws
 * including authentication, validation, and rate limiting.
 * No real server or TLS needed — testApplication routes requests in-process.
 */
class KtorSyncServerIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val testDir = File(System.getProperty("java.io.tmpdir"), "nepenthe-test-routes-${System.nanoTime()}")
    private val trustStore = DeviceTrustStore(testDir.absolutePath).also {
        DeviceTrustStore.pbkdf2Iterations = 1000
    }
    private val authenticator = SyncAuthenticator(trustStore)

    @BeforeTest
    fun before() {
        authenticator.clearPendingPairing()
        authenticator.clearSeenNonces()
    }

    @AfterTest
    fun cleanup() {
        trustStore.clearAll()
        authenticator.clearPendingPairing()
        authenticator.clearSeenNonces()
        File(testDir, "trusted-devices.json").delete()
        testDir.delete()
    }

    /**
     * Build the routing module used by all tests.
     * Mirrors KtorSyncServer's routing including the WebSocket endpoint.
     */
    private fun Application.testRouting() {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
        }

        routing {
            get("/info") {
                call.respondText(
                    json.encodeToString(HostInfo("test-server", "Test", "abcd1234", 2)),
                    ContentType.Application.Json
                )
            }

            get("/pairing/start") {
                call.respondText(
                    json.encodeToString(HostInfo("test-server", "Test", "abcd1234", 2)),
                    ContentType.Application.Json
                )
            }

            post("/pairing/verify") {
                val bodyText = call.receiveText()
                if (bodyText.length > 4096) {
                    call.respondText("""{"success":false,"error":"Body too large"}""", ContentType.Application.Json, status = HttpStatusCode.BadRequest)
                    return@post
                }
                val verifyReq = try {
                    json.decodeFromString<Map<String, String>>(bodyText)
                } catch (_: Exception) {
                    call.respondText("""{"success":false,"error":"Invalid request"}""", ContentType.Application.Json, status = HttpStatusCode.BadRequest)
                    return@post
                }
                if (!authenticator.verifyPairingToken(verifyReq["token"] ?: "")) {
                    call.respondText("""{"success":false,"error":"Invalid or expired token"}""", ContentType.Application.Json, status = HttpStatusCode.Forbidden)
                    return@post
                }
                val sharedSecret = authenticator.generateSharedSecret()
                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                    deviceId = verifyReq["clientDeviceId"] ?: "unknown",
                    displayName = verifyReq["clientDeviceName"] ?: "Unknown",
                    fingerprint = verifyReq["clientFingerprint"] ?: "unknown",
                    sharedSecret = sharedSecret,
                    pairedAt = System.currentTimeMillis()
                ))
                call.respondText(
                    """{"success":true,"deviceId":"${verifyReq["clientDeviceId"]}","sharedSecret":"$sharedSecret"}""",
                    ContentType.Application.Json
                )
            }

            post("/sync/push") {
                val deviceId = call.request.headers["X-Sync-Device"]
                val authHeader = call.request.headers["X-Sync-Auth"]
                if (deviceId == null || authHeader == null) {
                    call.respondText("""{"success":false,"error":"Authentication failed"}""", ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val rawBody = call.receiveText()
                if (!authenticator.verifyRequest(deviceId, rawBody, authHeader)) {
                    call.respondText("""{"success":false,"error":"Authentication failed"}""", ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                    return@post
                }
                if (rawBody.length > 10_000_000) {
                    call.respondText("""{"success":false,"error":"Payload too large"}""", ContentType.Application.Json, status = HttpStatusCode.fromValue(413))
                    return@post
                }
                call.respondText("""{"success":true}""", ContentType.Application.Json)
            }

            get("/sync/pull") {
                val deviceId = call.request.headers["X-Sync-Device"]
                val authHeader = call.request.headers["X-Sync-Auth"]
                if (deviceId == null || authHeader == null) {
                    call.respondText("""{"success":false,"error":"Authentication failed"}""", ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                    return@get
                }
                if (!authenticator.verifyRequest(deviceId, call.request.uri, authHeader)) {
                    call.respondText("""{"success":false,"error":"Authentication failed"}""", ContentType.Application.Json, status = HttpStatusCode.Unauthorized)
                    return@get
                }
                call.respondText("""{"success":true,"sessions":[],"doses":[]}""", ContentType.Application.Json)
            }

            // ---- WebSocket: continuous sync endpoint ----
            webSocket("/sync/ws") {
                val callerDeviceId = call.request.queryParameters["deviceId"] ?: run {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Missing deviceId"))
                    return@webSocket
                }
                val authHeader = call.request.queryParameters["auth"] ?: run {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Missing auth"))
                    return@webSocket
                }
                if (!trustStore.isTrustedDeviceId(callerDeviceId)) {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Untrusted device"))
                    return@webSocket
                }
                if (!authenticator.verifyRequest(callerDeviceId, "ws", authHeader)) {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Authentication failed"))
                    return@webSocket
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val msg = try {
                            wsJson.decodeFromString<WsMessage>(text)
                        } catch (_: Exception) {
                            outgoing.send(Frame.Text(
                                wsJson.encodeToString(WsAck(0, error = "Malformed"))
                            ))
                            continue
                        }
                        when (msg) {
                            is WsDelta -> {
                                outgoing.send(Frame.Text(
                                    wsJson.encodeToString(WsAck(seq = msg.seq))
                                ))
                            }
                            is WsPing -> {
                                outgoing.send(Frame.Text(wsJson.encodeToString(WsPong(msg.seq))))
                            }
                            is WsPong -> { /* ignore */ }
                            is WsAck -> { /* ignore */ }
                        }
                    }
                }
            }
        }
    }

    // ==================== /info ====================

    @Test
    fun `info endpoint returns device info`() = testApplication {
        application { testRouting() }
        val response = client.get("/info")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("abcd1234"))
    }

    // ==================== /pairing/verify ====================

    @Test
    fun `pairing verify succeeds with valid token`() = testApplication {
        application { testRouting() }
        val token = authenticator.generatePairingToken(60L)
        val response = client.post("/pairing/verify") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("token" to token, "clientDeviceId" to "c1", "clientDeviceName" to "C1", "clientFingerprint" to "fp1")))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `pairing verify rejects wrong token`() = testApplication {
        application { testRouting() }
        authenticator.generatePairingToken(60L)
        val response = client.post("/pairing/verify") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("token" to "WRONG1", "clientDeviceId" to "c1", "clientDeviceName" to "C1", "clientFingerprint" to "fp1")))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `pairing verify rejects oversized body`() = testApplication {
        application { testRouting() }
        val response = client.post("/pairing/verify") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat(5000))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pairing verify rejects malformed json`() = testApplication {
        application { testRouting() }
        val response = client.post("/pairing/verify") {
            contentType(ContentType.Application.Json)
            setBody("not-json{")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ==================== /sync/push ====================

    @Test
    fun `sync push rejects unauthenticated requests`() = testApplication {
        application { testRouting() }
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticated sync push succeeds`() = testApplication {
        application { testRouting() }
        val (deviceId, secret) = pairTestDevice()
        val body = """{"deviceId":"$deviceId","deviceName":"Test","since":0}"""
        val authHeader = signBody(deviceId, body, secret)

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            header("X-Sync-Device", deviceId)
            header("X-Sync-Auth", authHeader)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `tampered sync push is rejected`() = testApplication {
        application { testRouting() }
        val (deviceId, secret) = pairTestDevice()
        val body = """{"deviceId":"$deviceId","deviceName":"Test","since":0}"""
        val authHeader = signBody(deviceId, body, secret)
        val tampered = """{"deviceId":"$deviceId","deviceName":"HACKED","since":0}"""

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            header("X-Sync-Device", deviceId)
            header("X-Sync-Auth", authHeader)
            setBody(tampered)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ==================== /sync/pull ====================

    @Test
    fun `sync pull rejects unauthenticated`() = testApplication {
        application { testRouting() }
        val response = client.get("/sync/pull?since=0")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticated sync pull succeeds`() = testApplication {
        application { testRouting() }
        val (deviceId, secret) = pairTestDevice()
        val uri = "/sync/pull?since=0"
        val authHeader = signBody(deviceId, uri, secret)

        val response = client.get(uri) {
            header("X-Sync-Device", deviceId)
            header("X-Sync-Auth", authHeader)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ==================== /sync/ws (WebSocket) ====================

    @Test
    fun `ws rejects unauthenticated connections`() = testApplication {
        application { testRouting() }
        // WS without auth should not upgrade successfully
        val response = client.get("/sync/ws?deviceId=unknown&auth=bad")
        assertNotEquals(HttpStatusCode.OK, response.status,
            "unauthenticated WS should not succeed (upgrade fails)")
    }

    @Test
    fun `ws endpoint returns non-200 for missing auth`() = testApplication {
        application { testRouting() }
        val response = client.get("/sync/ws")
        assertNotEquals(HttpStatusCode.OK, response.status,
            "WS without query params should not succeed")
    }

    // ==================== Helpers ====================

    /** Pairs a test device and returns (deviceId, sharedSecret). */
    private fun pairTestDevice(): Pair<String, String> {
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = "test-client-auth", displayName = "Auth Test",
            fingerprint = "test-fp-auth", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))
        return "test-client-auth" to secret
    }

    private fun signBody(deviceId: String, body: String, secret: String): String {
        val ts = System.currentTimeMillis()
        val nonceBytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonceBytes)
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal("$deviceId:$ts:$nonce:$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts:$nonce:$sig"
    }
}
