package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlinx.serialization.encodeToString

/**
 * Shared sync protocol contract.
 *
 * Defines the HTTP-level protocol that BOTH JVM and iOS transports follow.
 * Endpoint paths, request construction, response application, and HMAC
 * signing are extracted here so they can be tested in commonTest.
 *
 * Wire format uses SyncBatch (push) and SyncResponse (pull response)
 * — both serializable types already in commonMain.
 *
 * Encryption: sync bodies are AES-256-GCM encrypted. The wire body is
 * base64(encryptBody(json, aesKey)). HMAC is computed over the base64
 * ciphertext (encrypt-then-MAC). See SyncCrypto (commonMain) for the
 * expect/actual encryption primitives.
 */

// ========== Endpoint paths ==========

object SyncEndpoints {
    const val INFO = "/info"
    const val PAIRING_START = "/pairing/start"
    const val PAIRING_VERIFY = "/pairing/verify"
    const val SYNC_PUSH = "/sync/push"
    const val SYNC_PULL = "/sync/pull"
}

// ========== Auth header constants ==========

object SyncAuth {
    const val AUTH_HEADER = "X-Sync-Auth"
    const val DEVICE_ID_HEADER = "X-Sync-Device"
    const val TIMESTAMP_WINDOW_MS = 45_000L
}

/**
 * Pure-Kotlin HMAC-SHA256 hex digest.
 * Platform-specific: JVM uses javax.crypto.Mac, iOS uses Security.CCHmac.
 */
expect fun hmacSha256Hex(secret: ByteArray, data: ByteArray): String

/** Build the auth header value: "timestamp:nonce:hex-signature". */
fun buildAuthHeader(deviceId: String, body: String, secret: ByteArray, timestamp: Long, nonce: String): String {
    val payload = "$deviceId:$timestamp:$nonce:$body"
    val signature = hmacSha256Hex(secret, payload.encodeToByteArray())
    return "$timestamp:$nonce:$signature"
}

/**
 * Generate a random 32-char hex nonce using the platform CSPRNG
 * (SecureRandom on JVM, SecRandomCopyBytes on iOS). Nonces must not come
 * from a plain PRNG (audit L2).
 */
fun generateNonce(): String {
    val chars = "0123456789abcdef"
    val bytes = secureRandomBytes(16)
    val sb = StringBuilder(32)
    for (b in bytes) {
        sb.append(chars[(b.toInt() ushr 4) and 0x0F])
        sb.append(chars[b.toInt() and 0x0F])
    }
    return sb.toString()
}

// ========== Request builders ==========

/**
 * Build a SyncBatch from entities that changed after [since].
 * Returns null if nothing changed (skip the sync round-trip).
 */
fun buildSyncBatch(
    repo: JournalRepository,
    deviceId: String,
    deviceName: String,
    since: Long
): SyncBatch? {
    fun <T> changed(list: List<T>, since: Long, updatedAt: (T) -> Long): List<T> =
        list.filter { updatedAt(it) >= since }

    val sessions = changed(repo.sessions.value, since) { it.updatedAt }
    val doses = changed(repo.doses.value, since) { it.updatedAt }
    val substances = changed(repo.substances.value, since) { it.updatedAt }
    val effects = changed(repo.effects.value, since) { it.updatedAt }
    val interactions = changed(repo.interactions.value, since) { it.updatedAt }
    val notes = changed(repo.notes.value, since) { it.updatedAt }
    val timelineEvents = changed(repo.timelineEvents.value, since) { it.updatedAt }
    val customUnits = changed(repo.customUnits.value, since) { it.updatedAt }

    val total =
        sessions.size + doses.size + substances.size + effects.size +
        interactions.size + notes.size + timelineEvents.size + customUnits.size
    if (total == 0) return null

    return SyncBatch(
        deviceId = deviceId,
        deviceName = deviceName,
        since = since,
        sessions = sessions,
        doses = doses,
        substances = substances,
        effects = effects,
        interactions = interactions,
        notes = notes,
        timelineEvents = timelineEvents,
        customUnits = customUnits
    )
}

/**
 * Apply a SyncResponse to the local repository.
 * Merges all returned entities (upsert) with last-writer-wins by updatedAt,
 * so a replayed/stale response cannot roll back newer local data.
 */
fun applySyncResponse(repo: JournalRepository, response: SyncResponse) {
    repo.applyBatch(
        sessions = response.sessions,
        doses = response.doses,
        substances = response.substances,
        effects = response.effects,
        interactions = response.interactions,
        notes = response.notes,
        timelineEvents = response.timelineEvents,
        customUnits = response.customUnits,
        lastWriterWins = true
    )
}

/**
 * Serialized form of a sync push request: the body bytes and auth header.
 */
data class SyncPushRequest(
    val body: String,
    val authHeader: String,
    val deviceId: String
) {
    val bodyBytes: ByteArray get() = body.encodeToByteArray()

    companion object {
        /** Build a signed push request from a SyncBatch. */
        fun fromBatch(
            batch: SyncBatch,
            secret: ByteArray,
            deviceId: String
        ): SyncPushRequest {
            val json = AppJson.json
            val body = json.encodeToString(batch)
            val time = currentTimeMillis()
            val nonce = generateNonce()
            val auth = buildAuthHeader(deviceId, body, secret, time, nonce)
            return SyncPushRequest(body, auth, deviceId)
        }
    }
}

/**
 * Result of parsing and verifying a push request on the server side.
 */
data class VerifiedPush(
    val deviceId: String,
    val batch: SyncBatch,
    val body: String
)
