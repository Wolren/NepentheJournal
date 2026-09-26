package app.journal.sync

import app.journal.serde.AppJson
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.crypto.secureRandomBytes
import app.journal.util.currentTimeMillis
import kotlinx.serialization.encodeToString

/**
 * Shared sync protocol contract.
 *
 * Defines the HTTP-level protocol that BOTH JVM and iOS transports follow.
 * Endpoint paths, request construction, response application, and HMAC
 * signing are extracted here so they can be tested in commonTest.
 *
 * Wire format uses SyncBatch (push) and SyncResponse (pull response):
 * both serializable types already in commonMain.
 *
 * Encryption: sync bodies are AES-256-GCM encrypted. The wire body is
 * base64(encryptBody(json, aesKey)). HMAC is computed over the base64
 * ciphertext (encrypt-then-MAC). See SyncCrypto (commonMain) for the
 * expect/actual encryption primitives.
 */

// ========== Endpoint paths ==========

/**
 * Wire protocol version this build speaks and serves. Both hosts advertise
 * it in HostInfo.protocolVersion; clients compare it at pairing and first
 * sync. A mismatch is WARN-ONLY on the JVM client (appendDebug warning plus
 * a lastWarning status line, never lastError and never a hard reject): warn
 * vs reject was not pinned by the contract, and SYNC-JVM chose warn so an
 * older peer keeps working while the user is told to upgrade.
 */
const val SYNC_PROTOCOL_VERSION = 2

object SyncEndpoints {
    /**
     * URL scheme for every sync endpoint. Pinned to "http" by the ECDH
     * pairing contract (section g); clients and servers must build endpoint
     * URLs from this constant so a future TLS phase flips one value instead
     * of hunting string literals.
     */
    const val URL_SCHEME = "http"
    const val INFO = "/info"
    const val PAIRING_START = "/pairing/start"
    const val PAIRING_VERIFY = "/pairing/verify"
    const val AUTH_VERIFY = "/auth/verify"
    const val SYNC_PUSH = "/sync/push"
    const val SYNC_PULL = "/sync/pull"
    const val SYNC_WS = "/sync/ws"
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
    repo: IJournalRepository,
    deviceId: String,
    deviceName: String,
    since: Long
): SyncBatch? {
    fun <T> changed(list: List<T>, since: Long, updatedAt: (T) -> Long): List<T> =
        list.filter { updatedAt(it) > since }

    val sessions = changed(repo.sessions.value, since) { it.updatedAt }
    val doses = changed(repo.doses.value, since) { it.updatedAt }
    val substances = changed(repo.substances.value, since) { it.updatedAt }
    val effects = changed(repo.effects.value, since) { it.updatedAt }
    val interactions = changed(repo.interactions.value, since) { it.updatedAt }
    val notes = changed(repo.notes.value, since) { it.updatedAt }
    val timelineEvents = changed(repo.timelineEvents.value, since) { it.updatedAt }
    val customUnits = changed(repo.customUnits.value, since) { it.updatedAt }

    val deleted = repo.deletedIdsSince(since)

    val total =
        sessions.size + doses.size + substances.size + effects.size +
        interactions.size + notes.size + timelineEvents.size + customUnits.size +
        deleted.deletedSessionIds.size + deleted.deletedDoseIds.size +
        deleted.deletedNoteIds.size + deleted.deletedSubstanceIds.size +
        deleted.deletedEffectIds.size + deleted.deletedInteractionIds.size +
        deleted.deletedTimelineEventIds.size + deleted.deletedCustomUnitIds.size
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
        customUnits = customUnits,
        deletedSessionIds = deleted.deletedSessionIds,
        deletedDoseIds = deleted.deletedDoseIds,
        deletedNoteIds = deleted.deletedNoteIds,
        deletedSubstanceIds = deleted.deletedSubstanceIds,
        deletedEffectIds = deleted.deletedEffectIds,
        deletedInteractionIds = deleted.deletedInteractionIds,
        deletedTimelineEventIds = deleted.deletedTimelineEventIds,
        deletedCustomUnitIds = deleted.deletedCustomUnitIds
    )
}

/**
 * Outcome of applying a SyncResponse locally. Skip counts report invalid
 * incoming entities and tombstone IDs that the client-response guard
 * rejected before they could reach the store (contract: client-response
 * guard). Callers log or surface them; they must NOT advance a cursor past
 * rejected data without noticing.
 */
data class SyncApplyResult(
    val appliedEntities: Int,
    val skippedEntities: Int,
    val skippedTombstones: Int
)

/**
 * Apply a SyncResponse to the local repository.
 * Merges all returned entities (upsert) with last-writer-wins by updatedAt,
 * so a replayed/stale response cannot roll back newer local data.
 *
 * Every incoming entity passes validateSyncResponse first: blank or
 * over-long ids, over-cap fields, and out-of-range timestamps are skipped
 * and counted instead of being stored. Tombstone IDs get the same shape
 * check. Skips are logged through the SyncContract tag.
 */
fun applySyncResponse(
    repo: IJournalRepository,
    response: SyncResponse,
    since: Long = 0L
): SyncApplyResult {
    val filtered = validateSyncResponse(response)
    if (filtered.skippedEntities > 0 || filtered.skippedTombstones > 0) {
        Log.withTag("SyncContract").w {
            "applySyncResponse rejected ${filtered.skippedEntities} invalid entities and " +
                "${filtered.skippedTombstones} invalid tombstone IDs"
        }
    }
    val clean = filtered.response
    repo.applyBatch(
        sessions = clean.sessions,
        doses = clean.doses,
        substances = clean.substances,
        effects = clean.effects,
        interactions = clean.interactions,
        notes = clean.notes,
        timelineEvents = clean.timelineEvents,
        customUnits = clean.customUnits,
        lastWriterWins = true,
        deletedSessionIds = clean.deletedSessionIds,
        deletedDoseIds = clean.deletedDoseIds,
        deletedNoteIds = clean.deletedNoteIds,
        deletedSubstanceIds = clean.deletedSubstanceIds,
        deletedEffectIds = clean.deletedEffectIds,
        deletedInteractionIds = clean.deletedInteractionIds,
        deletedTimelineEventIds = clean.deletedTimelineEventIds,
        deletedCustomUnitIds = clean.deletedCustomUnitIds,
        tombstoneCutoff = since
    )
    val applied = clean.sessions.size + clean.doses.size + clean.substances.size +
        clean.effects.size + clean.interactions.size + clean.notes.size +
        clean.timelineEvents.size + clean.customUnits.size
    return SyncApplyResult(
        appliedEntities = applied,
        skippedEntities = filtered.skippedEntities,
        skippedTombstones = filtered.skippedTombstones
    )
}

/**
 * Composite pull-cursor rule shared by BOTH hosts' page handlers (JVM
 * SyncServerHandlers.handlePull and iOS IosSyncServerRouter.buildSyncResponse).
 *
 * Two cursor shapes:
 *  - [sinceId] blank: [since] is a sync-cycle wall-clock cursor. The filter
 *    is INCLUSIVE (`updatedAt >= since`): an entity stamped in the same
 *    millisecond as the cursor but missed by the previous cycle must be
 *    served again rather than skipped. Duplicate delivery is idempotent
 *    (LWW apply), while a skip stays invisible until the cursor resets.
 *  - [sinceId] present: [since] is the composite resume position of a
 *    truncated page, and the filter is strictly-after in (updatedAt, id)
 *    order. With a total order the drain always makes progress inside a
 *    group of tied timestamps and never skips a row.
 *
 * The id is compared against REPOSITORY ids (decoded); the wire carries it
 * in SyncResponse.nextSinceId and the `sinceId` pull query parameter (which
 * the server decodes before this comparison).
 */
fun isAfterPullCursor(updatedAt: Long, id: String, since: Long, sinceId: String): Boolean =
    if (sinceId.isEmpty()) {
        updatedAt >= since
    } else {
        updatedAt > since || (updatedAt == since && id > sinceId)
    }

/**
 * Highest updatedAt across every entity list in a sync response.
 * Used to advance pull cursors. Tombstones carry no wire timestamps, so
 * they never move the cursor on their own (their deletion timestamps live
 * only in the sender tombstone journal).
 */
fun SyncResponse.maxUpdatedAt(): Long {
    var max = 0L
    fun consider(ts: Long) { if (ts > max) max = ts }
    sessions.forEach { consider(it.updatedAt) }
    doses.forEach { consider(it.updatedAt) }
    substances.forEach { consider(it.updatedAt) }
    effects.forEach { consider(it.updatedAt) }
    interactions.forEach { consider(it.updatedAt) }
    notes.forEach { consider(it.updatedAt) }
    timelineEvents.forEach { consider(it.updatedAt) }
    customUnits.forEach { consider(it.updatedAt) }
    return max
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

