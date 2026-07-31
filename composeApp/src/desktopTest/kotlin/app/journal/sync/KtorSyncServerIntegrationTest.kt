package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.sync.DeviceTrustStore.TrustedPeer
import app.journal.sync.aesEncryptionKey
import app.journal.sync.base64Encode
import app.journal.sync.encryptBody
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
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

    private fun Application.installRouter() {
        SyncServerRouter(
            repo = repo, trustStore = trustStore, authenticator = authenticator,
            onConnection = {}, deviceId = "test-device-abc123",
            deviceName = "Test Device", fingerprint = "abc123def456"
        ).installRouting(this)
    }

    companion object {
        fun extractField(json: String, field: String): String? {
            val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)
        }
    }
}
