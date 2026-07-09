package app.journal.sync

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Pairing flow — token + TLS certificate fingerprint, no passwords:
 *
 * HOST:
 *  1. generatePairingOffer() → displays 6-char token + QR
 *     QR payload: JSON PairingOffer (token, fingerprint, ip, port, expiry)
 *  2. Listener authenticates via ListenerCertificateAuthenticator:
 *     checks that incoming client cert fingerprint is in trusted Device records.
 *
 * JOINER:
 *  3. Scans QR or enters token manually.
 *  4. consumePairingOffer() verifies token match + not expired.
 *  5. Connects to host, pins the fingerprint for future TLS authentication.
 *  6. Both sides persist a Device record with the other's fingerprint.
 *
 * SUBSEQUENT SYNCS (no token needed):
 *  7. Replicator uses pinnedServerCertificate (host fingerprint).
 *  8. Listener uses ListenerCertificateAuthenticator (client fingerprint lookup).
 *
 * REVOCATION: delete Device record; refuse connections from that fingerprint.
 */
@Serializable
data class PairingOffer(
    val token: String,
    val hostFingerprint: String,  // SHA-256 hex of host TLS DER cert
    val hostAddress: String,
    val listenerPort: Int,
    val issuedAt: Long,
    val expiresAt: Long           // issuedAt + validForSeconds * 1000
)

sealed class PairingResult {
    data class Success(val deviceId: String, val displayName: String) : PairingResult()
    data object Expired : PairingResult()
    data object TokenMismatch : PairingResult()
    data class Error(val reason: String) : PairingResult()
}

interface PairingManager {
    suspend fun generatePairingOffer(validForSeconds: Long = 120L, port: Int = 4984): PairingOffer
    suspend fun consumePairingOffer(offer: PairingOffer, enteredToken: String): PairingResult
    suspend fun revokeDevice(deviceId: String)
    suspend fun trustedDevices(): List<TrustedDevice>
}

@Serializable
data class TrustedDevice(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val pairedAt: Long,
    val lastSeenAt: Long?
)

/** Avoids visually ambiguous chars (0/O, 1/I/l). */
fun generatePairingToken(length: Int = 6): String {
    val alpha = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    return (1..length).map { alpha[Random.nextInt(alpha.length)] }.joinToString("")
}
