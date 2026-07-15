package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.sync.DeviceTrustStore.TrustedPeer
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

            val pushBody = """{"deviceId":"paired-client","deviceName":"Client","since":0,"substances":[],"doses":[],"sessions":[],"interactions":[],"notes":[],"timelineEvents":[],"effects":[],"customUnits":[]}"""
            val authValue = authenticator.signRequest("paired-client", pushBody, sharedSecret)

            val push = client.post("/sync/push") {
                header("X-Sync-Device", "paired-client")
                header("X-Sync-Auth", authValue)
                contentType(ContentType.Application.Json)
                setBody(pushBody)
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
