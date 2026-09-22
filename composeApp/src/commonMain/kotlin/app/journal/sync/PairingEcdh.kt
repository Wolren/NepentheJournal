package app.journal.sync

/**
 * ECDH pairing key agreement (commonMain side of contract section g).
 *
 * Decision: pairing seals the permanent sharedSecret under an ECDH-derived
 * AES-256-GCM key instead of sending it in plaintext. This file holds the
 * shared serialization contract (field names), the shared KDF, and the shared
 * seal/unseal helpers built on the existing expect/actual primitives
 * (sha256, base64, encryptBody, decryptBody). Platform agents implement only
 * the key agreement itself:
 *
 *   JVM: java.security.KeyFactory + KeyAgreement ("ECDH", curve secp256r1)
 *   iOS: Security framework SecKeyCreateKeyExchange (kSecKeyKeyTypeECDH, 256 bit)
 *
 * Pinned wire spec:
 *   - Curve: NIST P-256 (secp256r1) on both platforms.
 *   - Public key encoding: ANSI X9.62 uncompressed point
 *     0x04 || X || Y (65 bytes), standard base64.
 *   - Client key: EPHEMERAL, freshly generated per pairing attempt and
 *     discarded when the attempt ends. Sent in PairingVerifyRequest as
 *     [CLIENT_PUBLIC_KEY_FIELD] (POST /pairing/verify; the pairing START
 *     step is a body-less GET, so the key rides on the verify body).
 *   - Host key: STATIC, generated once and persisted with the device
 *     identity. Advertised in HostInfo as [HOST_PUBLIC_KEY_FIELD] on both
 *     GET /info and GET /pairing/start, so the client has it before the
 *     token is entered.
 *   - Agreement: both sides compute the raw P-256 shared secret. JVM
 *     KeyAgreement.generateSecret() returns the 32-byte X coordinate; the
 *     iOS API may return the 65-byte X9.63 form (0x04 || X || Y).
 *     [normalizeSharedSecret] accepts either and reduces to the 32-byte X.
 *   - KDF: wrapKey = SHA-256(X || UTF-8(KDF_INFO)). Domain separation only;
 *     no salt, no counter (single-use key).
 *   - Sealing: ecdhSecretB64 = base64(encryptBody(sharedSecret, wrapKey)),
 *     reusing the existing AES-256-GCM payload layout (12-byte IV prefix).
 *   - The plaintext PairingResultResponse.sharedSecret field is REMOVED from
 *     the protocol: hosts stop populating it, clients stop accepting it.
 *     Pairing fails closed when [SEALED_SECRET_FIELD] is absent.
 *
 * Residual risk (documented, accepted): an ACTIVE man-in-the-middle present
 * only during the pairing window can substitute its own keys, because the
 * host public key still travels on plaintext HTTP. The contract choice fixes
 * audit C4 (passive LAN capture of the permanent secret). Defeating an
 * active pairing-time MITM requires authenticated transport (TLS with a
 * user-verified fingerprint) and is deferred until both hosts have cert
 * infrastructure; the iOS host has no self-signed identity today.
 *
 * Contract: docs/HARDENING-CONTRACTS-2026-09.md section (g).
 */
object PairingEcdh {
    /** Domain separation string mixed into the KDF. Byte-exact on all platforms. */
    const val KDF_INFO = "nepenthe-pairing-ecdh-v1"

    /** HostInfo field carrying the host's static P-256 public key (base64). */
    const val HOST_PUBLIC_KEY_FIELD = "ecdhPublicKeyB64"

    /** PairingVerifyRequest field carrying the client's ephemeral public key (base64). */
    const val CLIENT_PUBLIC_KEY_FIELD = "clientEcdhPublicKeyB64"

    /** PairingResultResponse field carrying base64(encryptBody(sharedSecret)). */
    const val SEALED_SECRET_FIELD = "ecdhSecretB64"

    /** Encoded uncompressed P-256 public key length in bytes (0x04 || X || Y). */
    const val PUBLIC_KEY_BYTES = 65

    /** Raw P-256 X coordinate length in bytes. */
    const val SHARED_SECRET_BYTES = 32

    /** Prefix byte of an ANSI X9.62 uncompressed point. */
    const val UNCOMPRESSED_PREFIX: Byte = 0x04

    /**
     * Reduce a raw ECDH shared secret to the 32-byte X coordinate.
     * Accepts either the 32-byte form or the 65-byte X9.63 form.
     * @throws IllegalArgumentException for any other length.
     */
    fun normalizeSharedSecret(raw: ByteArray): ByteArray = when {
        raw.size == SHARED_SECRET_BYTES -> raw
        raw.size == PUBLIC_KEY_BYTES && raw[0] == UNCOMPRESSED_PREFIX ->
            raw.copyOfRange(1, 1 + SHARED_SECRET_BYTES)
        else -> throw IllegalArgumentException(
            "ECDH shared secret must be 32 bytes or a 65-byte uncompressed point, got ${raw.size}"
        )
    }

    /** Derive the 32-byte AES-256 wrap key from the raw ECDH shared secret. */
    fun deriveWrapKey(ecdhSharedSecret: ByteArray): ByteArray {
        val x = normalizeSharedSecret(ecdhSharedSecret)
        val input = x + KDF_INFO.encodeToByteArray()
        return sha256(input)
    }

    /**
     * Host side: seal the freshly minted sharedSecret under the ECDH-derived
     * key. Returns base64(encryptBody(sharedSecret)) for the
     * [SEALED_SECRET_FIELD] response field.
     */
    fun wrapSharedSecret(ecdhSharedSecret: ByteArray, sharedSecret: String): String {
        val key = deriveWrapKey(ecdhSharedSecret)
        return try {
            base64Encode(encryptBody(sharedSecret, key))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Client side: unseal [ecdhSecretB64] with the same ECDH-derived key.
     * @throws IllegalArgumentException on malformed base64 or a GCM tag mismatch
     * (wrong key, tampered ciphertext).
     */
    fun unwrapSharedSecret(ecdhSharedSecret: ByteArray, ecdhSecretB64: String): String {
        val key = deriveWrapKey(ecdhSharedSecret)
        return try {
            decryptBody(base64Decode(ecdhSecretB64), key)
        } finally {
            key.fill(0)
        }
    }
}
