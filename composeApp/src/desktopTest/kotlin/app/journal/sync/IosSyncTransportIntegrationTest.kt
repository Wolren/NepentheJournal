package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/**
 * Integration tests verifying that the sync protocol (SyncBatch/SyncResponse,
 * HMAC auth, standard endpoints) used by IosSyncTransport is compatible with
 * standard Ktor test server infrastructure.
 *
 * Uses Ktor's in-process testApplication (no real port binding).
 */
class IosSyncTransportIntegrationTest {

    private val repo = JournalRepository()
    private val json = AppJson.json
    private val testSecret = "TEST_SECRET_32_BYTES_0123456789".encodeToByteArray()
    private val testDeviceId = "ios-test-device"

    @BeforeTest
    fun setUp() { repo.clearAll() }

    // ==========  Sync push contract tests  ==========

    @Test
    fun `push with valid HMAC is accepted`() = runBlocking {
        repo.upsertSession(Session(
            id = "s-push", title = "Push Test",
            createdAt = 1000L, updatedAt = 1000L,
            deviceOrigin = "test", startTime = 1000L
        ))

        testApplication {
            application { installTestServerRouting(repo, testSecret) }
            val batch = buildSyncBatch(repo, testDeviceId, "iOS Test", 0L)!!
            val req = SyncPushRequest.fromBatch(batch, testSecret, testDeviceId)

            val response = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                header(SyncAuth.DEVICE_ID_HEADER, req.deviceId)
                header(SyncAuth.AUTH_HEADER, req.authHeader)
                setBody(req.body)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<SyncResponse>(response.bodyAsText())
            assertTrue(body.success, "Valid HMAC push must succeed")
        }
    }

    @Test
    fun `push without auth headers rejected`() = runBlocking {
        testApplication {
            application { installTestServerRouting(repo, testSecret) }
            val response = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `push with wrong HMAC signature rejected`() = runBlocking {
        testApplication {
            application { installTestServerRouting(repo, testSecret) }

            val wrongSecret = "WRONG_SECRET_32_BYTES_9876543210".encodeToByteArray()
            val batch = SyncBatch(deviceId = "bad-device", deviceName = "", since = 0L)
            val req = SyncPushRequest.fromBatch(batch, wrongSecret, "bad-device")

            val response = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                header(SyncAuth.DEVICE_ID_HEADER, req.deviceId)
                header(SyncAuth.AUTH_HEADER, req.authHeader)
                setBody(req.body)
            }
            val body = json.decodeFromString<SyncResponse>(response.bodyAsText())
            assertFalse(body.success, "Wrong secret must be rejected")
        }
    }

    @Test
    fun `push with tampered body rejected`() = runBlocking {
        testApplication {
            application { installTestServerRouting(repo, testSecret) }

            val batch = SyncBatch(deviceId = testDeviceId, deviceName = "", since = 0L)
            val req = SyncPushRequest.fromBatch(batch, testSecret, testDeviceId)

            // Send the auth header but a DIFFERENT body (tampered in transit)
            val response = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                header(SyncAuth.DEVICE_ID_HEADER, req.deviceId)
                header(SyncAuth.AUTH_HEADER, req.authHeader)
                setBody("{\"tampered\": true}")
            }
            val body = json.decodeFromString<SyncResponse>(response.bodyAsText())
            assertFalse(body.success, "Tampered body must be rejected")
        }
    }

    // ==========  Info endpoint  ==========

    @Test
    fun `info returns host metadata`() = runBlocking {
        testApplication {
            application {
                routing {
                    get("/info") {
                        call.respondText(
                            json.encodeToString(HostInfo("dev-1", "Test iOS Host", "fp789", 2)),
                            ContentType.Application.Json
                        )
                    }
                }
            }
            val response = client.get("/info")
            val info = json.decodeFromString<HostInfo>(response.bodyAsText())
            assertEquals("dev-1", info.deviceId)
            assertEquals(2, info.protocolVersion)
        }
    }

    // ==========  Pairing protocol  ==========

    @Test
    fun `pairing start and verify round-trip`() = runBlocking {
        testApplication {
            application {
                routing {
                    get("/pairing/start") {
                        call.respondText(
                            json.encodeToString(HostInfo("host-1", "TestHost", "fp123", 2)),
                            ContentType.Application.Json
                        )
                    }
                    post("/pairing/verify") {
                        val body = call.receiveText()
                        val req = json.decodeFromString<PairingVerifyRequest>(body)
                        val secret = "paired-secret-${req.clientDeviceId}"
                        call.respondText(
                            json.encodeToString(PairingResultResponse(
                                success = true,
                                deviceId = "client-1",
                                sharedSecret = secret,
                                hostDeviceId = "host-1",
                                hostDeviceName = "TestHost",
                                hostFingerprint = "fp123"
                            )),
                            ContentType.Application.Json
                        )
                    }
                }
            }

            // Pairing start
            val startResp = client.get("/pairing/start")
            val info = json.decodeFromString<HostInfo>(startResp.bodyAsText())
            assertEquals("host-1", info.deviceId)

            // Pairing verify
            val verifyResp = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PairingVerifyRequest(
                    token = "ABC123",
                    clientDeviceId = "client-1",
                    clientDeviceName = "iPhone",
                    clientFingerprint = "fp-client"
                )))
            }
            val result = json.decodeFromString<PairingResultResponse>(verifyResp.bodyAsText())
            assertTrue(result.success)
            assertEquals("paired-secret-client-1", result.sharedSecret)
        }
    }

    @Test
    fun `pairing verify with invalid token rejected`() = runBlocking {
        testApplication {
            application {
                routing {
                    post("/pairing/verify") {
                        val body = call.receiveText()
                        val req = json.decodeFromString<PairingVerifyRequest>(body)
                        // Always reject
                        call.respondText(
                            json.encodeToString(PairingResultResponse(false, error = "Invalid token")),
                            ContentType.Application.Json
                        )
                    }
                }
            }

            val verifyResp = client.post("/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PairingVerifyRequest(
                    token = "BAD",
                    clientDeviceId = "client-1",
                    clientDeviceName = "iPhone",
                    clientFingerprint = "fp-client"
                )))
            }
            val result = json.decodeFromString<PairingResultResponse>(verifyResp.bodyAsText())
            assertFalse(result.success)
        }
    }

    // ==========  Wire format compatibility  ==========

    @Test
    fun `full sync exchange produces matching SyncBatch and SyncResponse`() = runBlocking {
        repo.upsertSession(Session(
            id = "s-exch", title = "Exchange",
            createdAt = 1000L, updatedAt = 1000L,
            deviceOrigin = "test", startTime = 1000L
        ))

        testApplication {
            application { installTestServerRouting(repo, testSecret) }

            val batch = buildSyncBatch(repo, testDeviceId, "iOS Test", 0L)!!
            val req = SyncPushRequest.fromBatch(batch, testSecret, testDeviceId)

            val response = client.post("/sync/push") {
                contentType(ContentType.Application.Json)
                header(SyncAuth.DEVICE_ID_HEADER, req.deviceId)
                header(SyncAuth.AUTH_HEADER, req.authHeader)
                setBody(req.body)
            }
            val syncResp = json.decodeFromString<SyncResponse>(response.bodyAsText())
            assertTrue(syncResp.success)
            assertTrue(syncResp.sessions.isNotEmpty())
            assertEquals("s-exch", syncResp.sessions.first().id)
        }
    }

    // ==========  Helpers  ==========

    /**
     * Install routing that mirrors the iOS sync server's push handler.
     * Verifies HMAC using the shared secret, then decodes and processes SyncBatch.
     */
    private fun Application.installTestServerRouting(
        repo: JournalRepository,
        expectedSecret: ByteArray
    ) {
        routing {
            post("/sync/push") {
                val deviceHeader = call.request.headers[SyncAuth.DEVICE_ID_HEADER]
                val authHeader = call.request.headers[SyncAuth.AUTH_HEADER]
                if (deviceHeader == null || authHeader == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Missing auth")),
                        ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val body = call.receiveText()
                val parts = authHeader.split(":", limit = 3)
                if (parts.size != 3) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Bad auth format")),
                        ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val payload = "$deviceHeader:${parts[0]}:${parts[1]}:$body"
                val expectedSig = hmacSha256Hex(expectedSecret, payload.encodeToByteArray())
                if (parts[2] != expectedSig) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "HMAC mismatch")),
                        ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val batch = json.decodeFromString<SyncBatch>(body)
                val response = SyncResponse(
                    success = true,
                    sessions = batch.sessions,
                    doses = batch.doses,
                    substances = batch.substances
                )
                call.respondText(
                    json.encodeToString(response),
                    ContentType.Application.Json
                )
            }
        }
    }
}
