package app.journal.sync

import app.journal.model.*
import kotlinx.serialization.Serializable

/**
 * Wire format for P2P sync over HTTP.
 * Both push (client → host) and pull (host → client) use this shape.
 */
@Serializable
data class SyncBatch(
    val deviceId: String,
    val deviceName: String,
    val since: Long,
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList(),
    val deletedSessionIds: List<String> = emptyList(),
    val deletedDoseIds: List<String> = emptyList(),
    val deletedNoteIds: List<String> = emptyList(),
    val deletedSubstanceIds: List<String> = emptyList(),
    val deletedEffectIds: List<String> = emptyList(),
    val deletedInteractionIds: List<String> = emptyList(),
    val deletedTimelineEventIds: List<String> = emptyList(),
    val deletedCustomUnitIds: List<String> = emptyList()
)

@Serializable
data class SyncResponse(
    val success: Boolean,
    val error: String? = null,
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList(),
    val deletedSessionIds: List<String> = emptyList(),
    val deletedDoseIds: List<String> = emptyList(),
    val deletedNoteIds: List<String> = emptyList(),
    val deletedSubstanceIds: List<String> = emptyList(),
    val deletedEffectIds: List<String> = emptyList(),
    val deletedInteractionIds: List<String> = emptyList(),
    val deletedTimelineEventIds: List<String> = emptyList(),
    val deletedCustomUnitIds: List<String> = emptyList(),
    val conflictsCreated: Int = 0,
    /** True when per-type caps cut this page; the client must keep pulling. */
    val truncated: Boolean = false,
    /** Low-water cursor for the next page. Valid only when [truncated]. */
    val nextSince: Long = 0L
)

@Serializable
data class HostInfo(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val protocolVersion: Int = 1,
    /**
     * True when this host serves the continuous-sync WebSocket endpoint
     * (/sync/ws). Clients MUST skip WebSocket use when this field is absent
     * or false (older hosts, and the iOS host today), falling back to HTTP
     * push/pull only. Defaults to false, so ignoreUnknownKeys decoding in
     * both directions stays safe. Contract section f.
     */
    val wsSupported: Boolean = false,
    /**
     * Host's STATIC P-256 ECDH public key: base64 of the 65-byte ANSI X9.62
     * uncompressed point (0x04 || X || Y). Null until the host implements the
     * ECDH pairing contract; clients MUST refuse to pair when it is absent.
     * Field name PairingEcdh.HOST_PUBLIC_KEY_FIELD. Contract section g.
     */
    val ecdhPublicKeyB64: String? = null
)

/**
 * Pairing request sent by a client to verify a pairing token with the host.
 */
@Serializable
data class PairingVerifyRequest(
    val token: String,
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientFingerprint: String,
    /**
     * Client's EPHEMERAL P-256 ECDH public key (base64, 65-byte uncompressed
     * point), fresh per pairing attempt. The host seals the sharedSecret under
     * the ECDH-derived key and returns it in PairingResultResponse.ecdhSecretB64.
     * Field name PairingEcdh.CLIENT_PUBLIC_KEY_FIELD. Null only from clients
     * that predate contract section g; hosts MUST reject such requests once
     * the SYNC-JVM/SYNC-IOS phases land.
     */
    val clientEcdhPublicKeyB64: String? = null
)

/**
 * Response to a pairing verification request.
 * On success, contains the shared secret for HMAC auth.
 *
 * C2 encrypted secret: [encSecretB64] carries base64(nonce || ciphertext || tag)
 * where the AES-256-GCM key is PBKDF2WithHmacSHA256(token, salt=clientDeviceId,
 * 100000 iterations, 256 bit). Clients must try [encSecretB64] first and fall
 * back to the legacy [sharedSecret] field, which stays populated.
 */
@Serializable
data class PairingResultResponse(
    val success: Boolean,
    val error: String? = null,
    val deviceId: String? = null,
    /**
     * LEGACY plaintext sharedSecret. Contract section g: hosts stop
     * populating this field and clients stop accepting it; it is deleted from
     * this DTO once both platform phases have removed their reads/writes.
     * Never emit it: a LAN observer of the pairing response must not learn
     * the permanent sync secret.
     */
    val sharedSecret: String? = null,
    /**
     * LEGACY token-wrapped secret (PBKDF2 over the pairing token). Superseded
     * by [ecdhSecretB64]: the unwrap key (the token) travels in the same
     * cleartext request body, so this field cannot resist a sniffer. Kept
     * only for wire compatibility until both platform phases drop it.
     */
    val encSecretB64: String? = null,
    val hostDeviceId: String? = null,
    val hostDeviceName: String? = null,
    val hostFingerprint: String? = null,
    /**
     * base64(encryptBody(sharedSecret, wrapKey)) where wrapKey is the
     * ECDH-derived key (PairingEcdh). This is the ONLY field that carries the
     * sharedSecret under contract section g; clients fail pairing closed when
     * it is absent.
     */
    val ecdhSecretB64: String? = null
)

/**
 * Host challenge-response: proves the host knows the shared secret for
 * [deviceId] without revealing it. The client sends a fresh random challenge
 * via /auth/verify, the host replies with HMAC-SHA256 of
 * "challenge:<deviceId>:<timestamp>:<challenge>" using the stored secret.
 * Prevents fingerprint-spoofed reconnects from reusing a stored secret.
 */
@Serializable
data class HostChallengeResponse(
    val timestamp: Long,
    val signature: String
)

/**
 * Result of a completed pairing, stored by the client.
 */
data class DevicePairingResult(
    val deviceId: String,
    val sharedSecret: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val hostFingerprint: String
)
