package app.journal.sync

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-based request authentication for sync protocol.
 *
 * Protocol:
 *   Authorization: HMAC-SHA256(deviceId + ":" + timestamp + ":" + nonce + ":" + body)
 *
 * The server verifies the signature using the shared secret stored during pairing.
 * Timestamps outside a 30-second window are rejected (with 15s clock skew allowance).
 * Nonces are tracked to prevent replay within the window.
 */

private val TIMESTAMP_WINDOW_MS = 45_000L // 45s: 30s validity + 15s clock skew
private val secureRandom = SecureRandom()

class SyncAuthenticator(private val trustStore: DeviceTrustStore) {

    // ---- Nonce replay protection ----
    // Bounded set of recently seen nonces to prevent replay attacks within the timestamp window.
    // Uses ConcurrentHashMap.newKeySet() for thread-safe add-and-check.
    private val seenNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val MAX_SEEN_NONCES = 5_000

    /**
     * Check and record a nonce for replay protection.
     * Returns true if the nonce is valid (within window + not seen before).
     */
    private fun checkAndRecordNonce(nonce: String, timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_WINDOW_MS) return false
        // Bound the set: prune entries whose timestamps are already outside the
        // window, then reject replay only if this exact nonce is still present.
        if (seenNonces.size > MAX_SEEN_NONCES) {
            val cutoff = now - TIMESTAMP_WINDOW_MS
            seenNonces.entries.removeIf { it.value < cutoff }
            // If still over limit, drop the oldest entries instead of killing the entire set
            while (seenNonces.size > MAX_SEEN_NONCES) {
                val oldest = seenNonces.minByOrNull { it.value }?.key ?: break
                seenNonces.remove(oldest)
            }
        }
        // putIfAbsent returns null only when the nonce was not already present.
        return seenNonces.putIfAbsent(nonce, timestamp) == null
    }

    /** Clear all seen nonces (e.g. on re-pairing). */
    fun clearSeenNonces() { seenNonces.clear() }

    // ---- Pairing tokens ----

    private data class PendingPairing(
        val token: String,
        val createdAt: Long,
        val ttlSeconds: Long = 120L
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - createdAt >= ttlSeconds * 1000
    }

    @Volatile
    private var pendingPairing: PendingPairing? = null

    /** Generate a 6-char pairing token valid for [ttlSeconds]. */
    fun generatePairingToken(ttlSeconds: Long = 120L): String {
        val alpha = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val token = (1..6).map { alpha[secureRandom.nextInt(alpha.length)] }.joinToString("")
        pendingPairing = PendingPairing(token, System.currentTimeMillis(), ttlSeconds)
        return token
    }

    /** Verify a pairing token and invalidate it (single-use). */
    fun verifyPairingToken(enteredToken: String): Boolean {
        val pending = pendingPairing
        if (pending == null || pending.isExpired) return false
        val matched = pending.token == enteredToken.uppercase().trim()
        if (matched) pendingPairing = null // single-use
        return matched
    }

    fun clearPendingPairing() { pendingPairing = null }

    /** Current pending token, if valid. */
    fun currentPairingToken(): String? {
        val p = pendingPairing
        return if (p != null && !p.isExpired) p.token else null
    }

    // ---- Shared secret generation ----

    fun generateSharedSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- HMAC signing ----

    /**
     * Sign a request body for the given device.
     * Returns the "timestamp:nonce:signature" header value.
     */
    fun signRequest(deviceId: String, body: String, secret: String): String {
        val timestamp = System.currentTimeMillis()
        val nonce = generateNonce()
        val signature = hmac(secret, "$deviceId:$timestamp:$nonce:$body")
        return "$timestamp:$nonce:$signature"
    }

    /**
     * Verify a signed request.
     * @param deviceId the claimed device ID
     * @param body the raw request body
     * @param authHeader the "timestamp:nonce:signature" from Authorization header
     * @return true if signature is valid and timestamp is within window
     */
    fun verifyRequest(
        deviceId: String,
        body: String,
        authHeader: String
    ): Boolean {
        val secret = trustStore.getSharedSecret(deviceId) ?: return false
        val parts = authHeader.split(":", limit = 3)
        if (parts.size != 3) return false

        val (timestampStr, nonce, signature) = parts
        val timestamp = timestampStr.toLongOrNull() ?: return false

        // Check timestamp window + nonce replay
        if (!checkAndRecordNonce(nonce, timestamp)) return false

        // Verify HMAC
        val expected = hmac(secret, "$deviceId:$timestamp:$nonce:$body")
        return constantTimeEquals(signature, expected)
    }

    /** Generate a signing header for pairing response (uses device's own secret). */
    fun signPairingResponse(deviceId: String, secret: String): String {
        val body = "pairing-ok:$deviceId"
        val timestamp = System.currentTimeMillis()
        val nonce = generateNonce()
        val signature = hmac(secret, "${deviceId}:$timestamp:$nonce:$body")
        return "$timestamp:$nonce:$signature"
    }

    /** Verify a pairing response signing header. */
    fun verifyPairingResponse(deviceId: String, authHeader: String, secret: String): Boolean {
        val parts = authHeader.split(":", limit = 3)
        if (parts.size != 3) return false
        val (timestampStr, nonce, signature) = parts
        val timestamp = timestampStr.toLongOrNull() ?: return false

        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_WINDOW_MS) return false

        val body = "pairing-ok:$deviceId"
        val expected = hmac(secret, "${deviceId}:$timestamp:$nonce:$body")
        return constantTimeEquals(signature, expected)
    }

    // ---- Private ----

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Prevent timing attacks on HMAC comparison. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    companion object {
        const val AUTH_HEADER = "X-Sync-Auth"
        const val DEVICE_ID_HEADER = "X-Sync-Device"

        /** Max body size for sync requests (10 MB). */
        const val MAX_SYNC_BODY_BYTES = 10L * 1024 * 1024
    }
}
