package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct coverage of the production retry path in KtorSyncClient
 * (private retryWithBackoff, KtorSyncClient.kt): an IOException-grade
 * transport failure must be retried with exponential backoff (1s, 2s) for
 * maxAttempts = 3 before the Result failure is returned.
 *
 * This replaces the deleted test-file-local retryOnFailure simulation, which
 * tested only its own helper and never touched production code (audit C7).
 * The function itself is private (prod change deferred and reported), so the
 * observable contract is exercised through the public pushChanges entry point
 * against a closed port: connection-refused is an IOException, so a correct
 * implementation spends at least 1000ms + 2000ms in backoff before failing.
 * A single immediate failure (no retry, or a non-IOException escaping the
 * retry classifier) finishes far below that floor and fails the assertion.
 */
class KtorSyncClientRetryTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun pushChangesRetriesTransportFailuresWithBackoffBeforeFailing() = runBlocking {
        // A port with no listener: connection refused on 127.0.0.1.
        val deadPort = freePort()

        val repo = JournalRepository()
        // Non-empty payload so pushChanges actually attempts a slice POST.
        repo.upsertSession(Session(
            id = "s:retry", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            deviceOrigin = "test", title = "Retry fixture", startTime = 1_700_000_000_000L
        ))

        val client = KtorSyncClient(
            repo = repo,
            deviceId = "retry-client",
            deviceName = "Retry Client",
            sharedSecret = "RETRY_SECRET_32_BYTES_0123456789AB"
        )
        try {
            val startedAt = System.nanoTime()
            val result = client.pushChanges(
                host = "127.0.0.1", port = deadPort,
                deviceId = "retry-client", deviceName = "Retry Client", since = 0L
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(result.isFailure,
                "push to a dead port must fail: ${result.exceptionOrNull()?.message}")
            // retryWithBackoff delays 1000ms after attempt 1 and 2000ms after
            // attempt 2 (attempt 3 returns the failure). Three real attempts
            // therefore take >= 3000ms; the floor allows clock granularity.
            assertTrue(elapsedMs >= 2_900,
                "expected 3 attempts with 1s+2s backoff (>= 2900ms), took ${elapsedMs}ms; " +
                    "retryWithBackoff did not retry. Last error: " +
                    "${result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}")
        } finally {
            client.close()
        }
    }

    @Test
    fun pushChangesFailsFastWhenNotPaired() = runBlocking {
        val repo = JournalRepository()
        val client = KtorSyncClient(repo = repo, deviceId = "np", deviceName = "NP")
        try {
            val startedAt = System.nanoTime()
            val result = client.pushChanges(
                host = "127.0.0.1", port = freePort(),
                deviceId = "np", deviceName = "NP", since = 0L
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(result.isFailure, "unpaired client must not push")
            assertEquals("Not paired", result.exceptionOrNull()?.message)
            assertTrue(elapsedMs < 2_000,
                "an unpaired client fails before any network retry: took ${elapsedMs}ms")
        } finally {
            client.close()
        }
    }
}
