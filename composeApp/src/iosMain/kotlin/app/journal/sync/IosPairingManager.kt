package app.journal.sync

import app.journal.util.currentTimeMillis

/**
 * iOS pairing token manager: single use 6 char token with 120s TTL.
 *
 * Mirrors the JVM SyncAuthenticator pairing contract on the subset the iOS
 * transport needs. Tokens use an unambiguous alphabet, are verified with a
 * constant time comparison, expire after [ttlSeconds], and are invalidated
 * on first successful use. Verification rejects before any secret is
 * minted, so unknown callers can never trigger secret generation.
 */
class IosPairingManager {

    private data class PendingPairing(
        val token: String,
        val createdAt: Long,
        val ttlSeconds: Long
    ) {
        fun isExpired(now: Long): Boolean = now - createdAt >= ttlSeconds * 1000
    }

    @kotlin.concurrent.Volatile
    private var pending: PendingPairing? = null

    /** Generate a 6 char pairing token valid for [ttlSeconds] (default 120s). */
    fun generatePairingToken(ttlSeconds: Long = 120L): String {
        val bytes = secureRandomBytes(6)
        val sb = StringBuilder(6)
        for (b in bytes) {
            sb.append(ALPHABET[(b.toInt() and 0xFF) % ALPHABET.length])
        }
        val token = sb.toString()
        pending = PendingPairing(token, currentTimeMillis(), ttlSeconds)
        return token
    }

    /** Verify a user entered token and invalidate it on success (single use). */
    fun verifyPairingToken(enteredToken: String): Boolean {
        val current = pending ?: return false
        if (current.isExpired(currentTimeMillis())) return false
        val matched = constantTimeEquals(current.token, enteredToken.uppercase().trim())
        if (matched) pending = null
        return matched
    }

    fun clearPendingPairing() {
        pending = null
    }

    /** Current pending token, or null when none is active or it expired. */
    fun currentPairingToken(): String? {
        val current = pending ?: return null
        return if (current.isExpired(currentTimeMillis())) null else current.token
    }

    /** Generate a fresh 32 byte shared secret as 64 hex chars. */
    fun generateSharedSecret(): String {
        val bytes = secureRandomBytes(32)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v shr 4])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * Sign a host identity challenge: HMAC-SHA256 of
     * "challenge:<deviceId>:<timestamp>:<challenge>" with the peer secret.
     * Same wire contract as the JVM SyncAuthenticator.signChallenge.
     */
    fun signChallenge(deviceId: String, timestamp: Long, challenge: String, secret: String): String =
        hmacSha256Hex(
            secret.encodeToByteArray(),
            "challenge:$deviceId:$timestamp:$challenge".encodeToByteArray()
        )

    /** Constant time comparison to avoid timing side channels. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    companion object {
        /** Unambiguous Crockford style alphabet: no 0/O, 1/I/L. */
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        /** Max pairing verify body: 4KB. */
        const val MAX_PAIRING_BODY_BYTES = 4096
    }
}
