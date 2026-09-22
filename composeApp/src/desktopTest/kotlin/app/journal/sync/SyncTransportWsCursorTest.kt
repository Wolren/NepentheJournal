package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Dose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract section a TEST obligation: a WsAck carrying an error must NOT
 * advance the client cursor (SyncTransport.handleWsAck: rejected delta =>
 * PendingWsPushes.failAll() => lastSyncTime untouched => HTTP fallback), while
 * the next accepted delta still reaches the server.
 *
 * This is the only genuinely end-to-end test in the suite: a real
 * KtorSyncServer on an ephemeral port (never a fixed one, sibling JVMs) plus
 * the production SyncTransport as the client. Observable contract:
 *  1. the rejected delta produces the "cursor held" debug line,
 *  2. status.lastSyncAt is still null at that moment (cursor did not move),
 *  3. a subsequent valid delta is applied on the server (pipeline alive).
 */
class SyncTransportWsCursorTest {

    private lateinit var serverDir: File
    private lateinit var clientDir: File
    private lateinit var server: KtorSyncServer
    private lateinit var transport: SyncTransport

    private val sharedSecret = "WSCURSOR_SECRET_0123456789ABCDEF"

    @BeforeTest
    fun setUp() {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        serverDir = File(base, "nepenthe-ws-server-${System.nanoTime()}").apply { mkdirs() }
        clientDir = File(base, "nepenthe-ws-client-${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { runBlocking { transport.disconnectFrom("device-serverhost") } }
        runCatching { server.stop() }
        serverDir.deleteRecursively()
        clientDir.deleteRecursively()
    }

    @Test
    fun wsAckErrorHoldsCursorAndNextValidDeltaStillLands() = runBlocking {
        // --- Server side ------------------------------------------------ ---
        val serverRepo = JournalRepository()
        val serverTrust = DeviceTrustStore(serverDir.absolutePath)
        val serverAuth = SyncAuthenticator(serverTrust)
        server = KtorSyncServer(
            repo = serverRepo, port = 0, // ephemeral: never collide with sibling test JVMs
            tlsIdentity = TlsIdentityManager(serverDir.absolutePath),
            ecdhIdentity = EcdhIdentityManager(serverDir.absolutePath),
            trustStore = serverTrust,
            authenticator = serverAuth,
            onConnection = {}
        )
        val info = server.start()
        val port = info.port

        // --- Client side: pre-seed identity BEFORE the transport so the
        // derived device id is known, then trust both directions. ----------
        val clientFingerprint = TlsIdentityManager(clientDir.absolutePath).ensureIdentity()
        val clientId = "device-${clientFingerprint.take(16)}"

        DeviceTrustStore(clientDir.absolutePath).addPeer(
            DeviceTrustStore.TrustedPeer(
                deviceId = "device-serverhost",
                displayName = "Server Host",
                fingerprint = "server-fp",
                sharedSecret = sharedSecret,
                pairedAt = System.currentTimeMillis()
            )
        )
        serverTrust.addPeer(
            DeviceTrustStore.TrustedPeer(
                deviceId = clientId,
                displayName = "Cursor Client",
                fingerprint = "cursor-client-fp",
                sharedSecret = sharedSecret,
                pairedAt = System.currentTimeMillis()
            )
        )

        val clientRepo = JournalRepository()
        transport = SyncTransport(clientRepo, clientDir.absolutePath)
        assertEquals(clientId, transport.deviceId,
            "pre-generated identity must reproduce the transport's device id")

        // Subscribe to the replayed debug log BEFORE connecting: replay = 200.
        val debugLines = mutableListOf<String>()
        val collector = launch {
            transport.observeDebugLog().collect { debugLines.add(it) }
        }

        transport.startContinuousSync(
            DiscoveredPeer(
                deviceId = "device-serverhost", displayName = "Server Host",
                host = "127.0.0.1", port = port, isTrusted = true,
                fingerprint = "server-fp"
            )
        )

        // Wait for the WS connection to register. updateStatus publishes the
        // WS registration as status.continuousPeers (activePeers is the HTTP
        // list and stays empty here). A failed connect surfaces as a
        // "WS connect:" lastError instead of hanging the wait.
        withTimeout(10_000) {
            while (transport.observeStatus().first().continuousPeers < 1) {
                val err = transport.observeStatus().first().lastError
                if (err != null && err.startsWith("WS connect")) {
                    kotlin.test.fail("transport never connected: $err")
                }
                delay(100)
            }
        }

        // --- 1. Trigger a REJECTED delta: negative dose fails validateWsDelta.
        val now = System.currentTimeMillis()
        val doseBad = Dose(
            id = "d:ws-bad", createdAt = now, updatedAt = now, deviceOrigin = "cursor-test",
            sessionId = "s:ws", substanceId = "cid:x",
            routeOfAdministration = "Oral", amount = -50.0, unit = "mg", timestamp = now
        )
        clientRepo.upsertDose(doseBad)

        assertTrue(clientRepo.mutationCount.value >= 1,
            "upsertDose must bump the shared mutationCount (WS push trigger)")

        // Deadline loop (not withTimeout) so a miss dumps full context
        // instead of a bare TimeoutCancellationException. The transport's
        // mutation collector uses drop(1) on a StateFlow: if its subscription
        // loses the start-up race it can miss the FIRST emission, so give it
        // a second emission if the line has not appeared after 6s.
        suspend fun dumpCtx(): String = buildString {
            appendLine("debug log (collector):")
            debugLines.forEach { appendLine("  $it") }
            appendLine("status: ${transport.observeStatus().first()}")
            appendLine("client mutationCount: ${clientRepo.mutationCount.value}")
            appendLine("client doses: ${clientRepo.doses.value.map { it.id to it.amount }}")
            appendLine("server doses: ${serverRepo.doses.value.map { it.id }}")
            appendLine("server sessions: ${serverRepo.sessions.value.map { it.id }}")
        }
        val startedAt = System.currentTimeMillis()
        var reTriggered = false
        while (System.currentTimeMillis() - startedAt < 15_000 &&
            debugLines.none { it.contains("cursor held") }
        ) {
            if (!reTriggered && System.currentTimeMillis() - startedAt > 6_000) {
                clientRepo.upsertDose(doseBad.copy(amount = -75.0))
                reTriggered = true
            }
            delay(100)
        }
        if (debugLines.none { it.contains("cursor held") }) {
            kotlin.test.fail("no held-cursor debug line within 15s: " + dumpCtx())
        }
        val heldLine = debugLines.first { it.contains("cursor held") }
        assertTrue(heldLine.contains("WS delta rejected"),
            "rejected WsAck must log the held-cursor path: $heldLine")

        // --- 2. The cursor never moved: lastSyncAt is still null. ---------
        assertNull(
            transport.observeStatus().first().lastSyncAt,
            "a rejected WsAck must NOT advance the sync cursor (lastSyncTime stays null)"
        )
        assertTrue(serverRepo.doses.value.none { it.id == "d:ws-bad" },
            "the rejected delta must never reach the server repo")

        // The rejected dose stays in the client repo and every future delta
        // carries everything with updatedAt > since, so drop it now;
        // otherwise all later deltas would keep failing validation and stage 3
        // could never succeed. The delete ships as a tombstone.
        clientRepo.deleteDose("d:ws-bad")

        // --- 3. Pipeline stays alive: the next VALID delta is applied. ----
        clientRepo.upsertDose(Dose(
            id = "d:ws-good", createdAt = now, updatedAt = now, deviceOrigin = "cursor-test",
            sessionId = "s:ws", substanceId = "cid:x",
            routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = now
        ))
        val deadline2 = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline2 &&
            serverRepo.doses.value.none { it.id == "d:ws-good" }
        ) delay(100)
        if (serverRepo.doses.value.none { it.id == "d:ws-good" }) {
            kotlin.test.fail("valid delta never landed within 15s: " + dumpCtx())
        }
        assertNotNull(serverRepo.doses.value.first { it.id == "d:ws-good" })

        // Also pin that the good data arrived as an entity on the client's own
        // view (sanity: the fixture upsert worked).
        assertTrue(clientRepo.doses.value.any { it.id == "d:ws-good" })

        collector.cancel()
        transport.disconnectFrom("device-serverhost")
        // Keep a live session open for the next assertion-free statement? No:
        // disconnect above closes it; server.stop runs in tearDown.
    }
}
