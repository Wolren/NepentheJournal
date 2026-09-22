package app.journal.sync

import app.journal.log.Log
import java.io.File
import java.math.BigInteger
import javax.crypto.KeyAgreement
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Static P-256 (secp256r1) keypair for ECDH pairing (contract section g).
 *
 * Generated once and persisted NEXT TO identity.p12 in the sync data
 * directory: the private key as AES-256-GCM encrypted PKCS#8 under the
 * shared at-rest key file, the public key as plain X.509
 * SubjectPublicKeyInfo. The host advertises the uncompressed point via
 * [publicKeyB64] in HostInfo on both GET /info and GET /pairing/start.
 *
 * Clients never persist a key: they generate an EPHEMERAL keypair per
 * pairing attempt through [generateEphemeralKeyPair] and discard it.
 *
 * Key material never enters the debug log or any sync response; only the
 * base64 public point travels on the wire and the sealed secret is
 * produced through [PairingEcdh.wrapSharedSecret].
 *
 * Rotation policy: unlike the device identity, this pairing key MAY be
 * regenerated when its stored copy is unreadable. Only in-flight pairings
 * at that instant are affected, and the advertisement updates on the next
 * HostInfo response; refusing to rotate would make pairing fail forever.
 */
class EcdhIdentityManager(private val dataDir: String) {

    private val privateFile: File get() = File(dataDir, "ecdh-p256-private.pk8")
    private val publicFile: File get() = File(dataDir, "ecdh-p256-public.pk8")
    private val atRestKey = AtRestKey(dataDir)

    /** Host static keypair, generated and persisted on first use. */
    val keyPair: KeyPair by lazy { loadOrGenerate() }

    /** Base64 of the 65-byte ANSI X9.62 uncompressed point (0x04 || X || Y). */
    val publicKeyB64: String by lazy { uncompressedPointB64(keyPair.public) }

    /** Raw P-256 shared secret (32-byte X coordinate) with an ephemeral peer key. */
    fun agreeWith(peerPublicKeyB64: String): ByteArray =
        agree(keyPair.private, peerPublicKeyB64)

    // ---- Private ----

    private fun loadOrGenerate(): KeyPair {
        tryLoad()?.let { return it }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE_NAME))
        val generated = kpg.generateKeyPair()
        try {
            File(dataDir).mkdirs()
            privateFile.writeBytes(encryptPrivate(generated.private.encoded))
            publicFile.writeBytes(generated.public.encoded)
        } catch (e: Exception) {
            // The in-memory key still serves this process; the next start
            // retries the write. Never log the key bytes themselves.
            Log.withTag("EcdhIdentity").e(e) { "Could not persist ECDH keypair; continuing in-memory" }
        }
        return generated
    }

    private fun tryLoad(): KeyPair? {
        if (!privateFile.exists() || !publicFile.exists()) return null
        return try {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(decryptPrivate(privateFile.readBytes())))
            val pub = kf.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
            KeyPair(pub, priv)
        } catch (e: Exception) {
            Log.withTag("EcdhIdentity").w { "ECDH keypair unreadable (${e.message}); regenerating" }
            null
        }
    }

    private fun encryptPrivate(der: ByteArray): ByteArray {
        val key = atRestKeyBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val nonce = cipher.iv
        return nonce + cipher.doFinal(der)
    }

    private fun decryptPrivate(blob: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES + 16) { "ECDH private key blob too short" }
        val key = atRestKeyBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, blob.copyOfRange(0, NONCE_BYTES))
        )
        return cipher.doFinal(blob.copyOfRange(NONCE_BYTES, blob.size))
    }

    /**
     * The at-rest key for sealing this file. Created only when no key file
     * exists at all: a corrupt or oversized at-rest.key must NOT be silently
     * replaced here, because rotating it would orphan identity.p12 and the
     * trust store, which are encrypted under the current key.
     */
    private fun atRestKeyBytes(): ByteArray {
        atRestKey.loadOrNull()?.let { return it }
        if (File(dataDir, "at-rest.key").exists()) {
            throw IllegalStateException("At-rest key file unreadable; refusing to rotate it for the ECDH key")
        }
        return atRestKey.create()
    }

    companion object {
        /** Curve name accepted by every JDK SunEC provider (NIST P-256 / secp256r1). */
        const val CURVE_NAME = "secp256r1"

        /** Encoded uncompressed point length: 0x04 || X(32) || Y(32). */
        const val PUBLIC_KEY_BYTES = 65

        /** Prefix byte of an ANSI X9.62 uncompressed point. */
        const val UNCOMPRESSED_PREFIX: Byte = 0x04

        private const val NONCE_BYTES = 12

        // NIST P-256 domain parameters, used ONLY for the explicit on-curve
        // check of an untrusted peer point before it reaches KeyAgreement.
        // y^2 = x^3 - 3x + b (mod p).
        private val FIELD_P = BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16
        )
        private val CURVE_B = BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16
        )
        private val CURVE_A_NEG_THREE = BigInteger.valueOf(3)

        /** Fresh EPHEMERAL keypair for one pairing attempt; discarded afterwards. */
        fun generateEphemeralKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(CURVE_NAME))
            return kpg.generateKeyPair()
        }

        /** Base64 of the uncompressed X9.62 point (65 bytes) for [public]. */
        fun uncompressedPointB64(public: PublicKey): String {
            val ec = public as? ECPublicKey
                ?: throw IllegalArgumentException("Not an EC public key")
            val point = ec.w
            val out = ByteArray(PUBLIC_KEY_BYTES)
            out[0] = UNCOMPRESSED_PREFIX
            writeFixed32(point.affineX, out, 1)
            writeFixed32(point.affineY, out, 33)
            return Base64.getEncoder().encodeToString(out)
        }

        /**
         * Decode and fully validate an untrusted base64 public key:
         * valid standard base64, exactly 65 bytes, 0x04 prefix, coordinates
         * inside the field, and a point ON the secp256r1 curve.
         *
         * @throws IllegalArgumentException on any failure; the pairing
         * handler maps this to success=false with HTTP 400 (contract g).
         */
        fun decodeUncompressedPoint(publicKeyB64: String): ECPoint {
            val raw = try {
                Base64.getDecoder().decode(publicKeyB64)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("ECDH public key is not valid base64")
            }
            if (raw.size != PUBLIC_KEY_BYTES) {
                throw IllegalArgumentException(
                    "ECDH public key must be $PUBLIC_KEY_BYTES bytes, got ${raw.size}"
                )
            }
            if (raw[0] != UNCOMPRESSED_PREFIX) {
                throw IllegalArgumentException("ECDH public key must be an uncompressed point (0x04 prefix)")
            }
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            if (!isOnCurve(x, y)) {
                throw IllegalArgumentException("ECDH public key is not a point on secp256r1")
            }
            return ECPoint(x, y)
        }

        /** P-256 curve check for untrusted coordinates. */
        fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
            if (x.signum() < 0 || x >= FIELD_P) return false
            if (y.signum() < 0 || y >= FIELD_P) return false
            val left = y.multiply(y).mod(FIELD_P)
            val right = x.multiply(x).mod(FIELD_P).multiply(x).mod(FIELD_P)
                .subtract(x.multiply(CURVE_A_NEG_THREE)).add(CURVE_B).mod(FIELD_P)
            return left == right
        }

        /**
         * ECDH agreement with a validated peer point. Returns the raw
         * 32-byte X coordinate of the shared secret (feed it to
         * [PairingEcdh.normalizeSharedSecret] only if the platform ever
         * returns the 65-byte form; the JVM returns 32 bytes).
         */
        fun agree(privateKey: PrivateKey, peerPublicKeyB64: String): ByteArray {
            val point = decodeUncompressedPoint(peerPublicKeyB64)
            val ecPrivate = privateKey as? java.security.interfaces.ECPrivateKey
                ?: throw IllegalArgumentException("Private key is not an EC key")
            val peerPublic = KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(point, ecPrivate.params))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(peerPublic, true)
            return agreement.generateSecret()
        }

        /** Right-align [value] into 32 big-endian bytes at [offset] of [out]. */
        private fun writeFixed32(value: BigInteger, out: ByteArray, offset: Int) {
            var bytes = value.toByteArray() // big-endian with sign byte
            if (bytes.size > 32 && bytes[0] == 0.toByte()) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            require(bytes.size <= 32) { "P-256 coordinate exceeds 32 bytes" }
            System.arraycopy(bytes, 0, out, offset + (32 - bytes.size), bytes.size)
        }
    }
}
