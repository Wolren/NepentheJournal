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
    val customUnits: List<CustomUnit> = emptyList()
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
    val conflictsCreated: Int = 0
)

@Serializable
data class HostInfo(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val protocolVersion: Int = 1
)

/**
 * Pairing request sent by a client to verify a pairing token with the host.
 */
@Serializable
data class PairingVerifyRequest(
    val token: String,
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientFingerprint: String
)

/**
 * Response to a pairing verification request.
 * On success, contains the shared secret for HMAC auth.
 */
@Serializable
data class PairingResultResponse(
    val success: Boolean,
    val error: String? = null,
    val deviceId: String? = null,
    val sharedSecret: String? = null,
    val hostDeviceId: String? = null,
    val hostDeviceName: String? = null,
    val hostFingerprint: String? = null
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
