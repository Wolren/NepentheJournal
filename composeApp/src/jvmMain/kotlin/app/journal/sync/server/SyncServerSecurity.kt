package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.serde.AppJson
import app.journal.log.Log
import app.journal.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.journal.sync.aesEncryptionKey
import app.journal.sync.base64Decode
import app.journal.sync.base64Encode
import app.journal.sync.decryptBody
import app.journal.sync.encryptBody
import java.util.concurrent.ConcurrentHashMap
internal class SyncServerSecurity(
    private val trustStore: DeviceTrustStore,
    private val authenticator: SyncAuthenticator,
    private val deviceId: String,
    private val deviceName: String,
    private val fingerprint: String,
    private val pairingAttempts: ConcurrentHashMap<String, Pair<Int, Long>>
) {

    fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        // Atomic read-modify-write: ConcurrentHashMap.compute prevents TOCTOU between
        // reading the current count and writing the updated value.
        val state = pairingAttempts.compute(clientIp) { _, current ->
            val (count, windowStart) = current ?: Pair(0, now)
            if (now - windowStart > 120_000) Pair(1, now) // new window
            else Pair(count + 1, windowStart)
        } ?: Pair(1, now)
        return state.first > 5
    }

    /** Drop rate-limit buckets whose window expired so idle IPs never accumulate. */
    fun evictStaleBuckets() {
        val now = System.currentTimeMillis()
        pairingAttempts.entries.removeIf { now - it.value.second > 120_000 }
    }

    /**
     * Drop entries of a per-endpoint throttle whose window expired.
     * Generic form of [evictStaleBuckets] for the push/pull/verify maps.
     */
    fun evictStaleThrottle(
        throttle: ConcurrentHashMap<String, Pair<Int, Long>>,
        windowMs: Long
    ) {
        val now = System.currentTimeMillis()
        throttle.entries.removeIf { now - it.value.second > windowMs }
    }

    /**
     * Generic per-IP throttle. Returns true when [ip] exceeded [max] requests
     * in the current window. Atomic via ConcurrentHashMap.compute, same as
     * the pairing limiter. Allows exactly [max], blocks the max+1th.
     */
    fun isThrottled(
        throttle: ConcurrentHashMap<String, Pair<Int, Long>>,
        ip: String,
        max: Int,
        windowMs: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val state = throttle.compute(ip) { _, current ->
            val (count, windowStart) = current ?: Pair(0, now)
            if (now - windowStart > windowMs) Pair(1, now) // new window
            else Pair(count + 1, windowStart)
        } ?: Pair(1, now)
        return state.first > max
    }

    /**
     * Strict Content-Length pre-check: the request is valid only when it
     * declares a length AND it fits in [maxBytes]. Missing or chunked bodies
     * are refused unread, since Ktor receiveText has no size cap and would
     * otherwise buffer an unbounded body before any check runs.
     */
    fun hasValidContentLength(call: ApplicationCall, maxBytes: Long): Boolean {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return false
        return declared in 1..maxBytes
    }

    /**
     * Warn log for auth failures. Includes peer IP, device ID, endpoint, and
     * reason. Never logs secrets, tokens, auth headers, or bodies.
     */
    fun warnAuth(call: ApplicationCall, deviceId: String?, endpoint: String, reason: String) {
        val peer = try { call.request.local.remoteHost } catch (_: Exception) { "unknown" }
        val safeDevice = deviceId?.take(64) ?: "unknown"
        Log.withTag("KtorSyncServer").w { "auth denied peer=$peer device=$safeDevice endpoint=$endpoint reason=$reason" }
    }

    suspend fun verifyRequest(call: ApplicationCall): Pair<String, String>? {
        val callerDeviceId = call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER] ?: return null
        val authHeader = call.request.headers[SyncAuthenticator.AUTH_HEADER] ?: return null
        if (!trustStore.isTrustedDeviceId(callerDeviceId)) return null

        val body = when (call.request.httpMethod.value) {
            "POST" -> call.receiveText()
            "GET" -> call.request.uri
            else -> return null
        }

        return if (authenticator.verifyRequest(callerDeviceId, body, authHeader)) {
            Pair(callerDeviceId, body)
        } else null
    }

    /** Ensure the host's own peer record exists in the trust store. */
    fun hostSecret(): String {
        return trustStore.getSharedSecret(deviceId)
            ?: authenticator.generateSharedSecret().also {
                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                    deviceId = deviceId,
                    displayName = deviceName,
                    fingerprint = fingerprint,
                    sharedSecret = it,
                    pairedAt = System.currentTimeMillis()
                ))
            }
    }
}
