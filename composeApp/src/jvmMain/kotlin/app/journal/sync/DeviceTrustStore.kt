package app.journal.sync

import app.journal.data.AppJson
import app.journal.log.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Persists the set of trusted devices that have completed pairing.
 *
 * Stored as JSON at ~/.nepenthe/trusted-devices.json.
 * Each record contains the device's fingerprint (SHA-256 of TLS cert),
 * a display name, and the shared HMAC secret exchanged during pairing.
 *
 * Secrets are encrypted at rest using AES-256-GCM with a key derived via
 * PBKDF2WithHmacSHA256 (100k iterations) and a random salt stored alongside
 * the ciphertext. The salt is generated on first save and never reused
 * across distinct trust-store files.
 *
 * The store is cached in memory after first load; all public methods
 * use a single [lock] for thread safety.
 */
class DeviceTrustStore(private val dataDir: String = platformSyncDataDir()) {

    private val lock = Any()

    @Serializable
    data class TrustedPeer(
        val deviceId: String,
        val displayName: String,
        val fingerprint: String,
        val sharedSecret: String,
        val pairedAt: Long,
        val lastSeenAt: Long? = null
    )

    @Serializable
    private data class TrustStore(
        val version: Int = 2,
        /** Random hex salt for PBKDF2. Empty = pre-PBKDF2 (migration path). */
        val salt: String = "",
        val peers: List<TrustedPeer> = emptyList()
    )

    private val file: File get() = File(dataDir, "trusted-devices.json")
    private val tmpFile: File get() = File(dataDir, "trusted-devices.json.tmp")
    private val json get() = AppJson.json
    override fun toString(): String = json.encodeToString(this)

    /** In-memory cache of the DECRYPTED store — loaded once, invalidated on writes. */
    @Volatile
    private var cachedStore: TrustStore? = null

    /** The current PBKDF2 salt (stable while the store file exists). */
    @Volatile
    private var currentSalt: String? = null

    fun addPeer(peer: TrustedPeer) = synchronized(lock) {
        val store = loadStore()
        val updated = store.copy(peers = store.peers.filter { it.deviceId != peer.deviceId } + peer)
        saveStore(updated)
    }

    fun updateLastSeen(deviceId: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        val store = loadStore()
        val updated = store.copy(peers = store.peers.map {
            if (it.deviceId == deviceId) it.copy(lastSeenAt = at) else it
        })
        saveStore(updated)
    }

    fun isTrusted(fingerprint: String): Boolean = synchronized(lock) {
        loadStore().peers.any { it.fingerprint == fingerprint }
    }

    fun isTrustedDeviceId(deviceId: String): Boolean = synchronized(lock) {
        loadStore().peers.any { it.deviceId == deviceId }
    }

    fun getSharedSecret(deviceId: String): String? = synchronized(lock) {
        loadStore().peers.find { it.deviceId == deviceId }?.sharedSecret
    }

    fun getPeer(fingerprint: String): TrustedPeer? = synchronized(lock) {
        loadStore().peers.find { it.fingerprint == fingerprint }
    }

    fun getPeerById(deviceId: String): TrustedPeer? = synchronized(lock) {
        loadStore().peers.find { it.deviceId == deviceId }
    }

    fun listPeers(): List<TrustedPeer> = synchronized(lock) {
        loadStore().peers
    }

    fun revokeDevice(deviceId: String) = synchronized(lock) {
        val store = loadStore()
        saveStore(store.copy(peers = store.peers.filter { it.deviceId != deviceId }))
    }

    fun clearAll() = synchronized(lock) {
        saveStore(TrustStore(salt = generateSalt()))
    }

    fun count(): Int = synchronized(lock) {
        loadStore().peers.size
    }

    // ---- Private ----

    /**
     * Load the trust store from disk (or cache), transparently migrating
     * from legacy encryption (SHA-256) and pre-encryption (plaintext).
     *
     * Always returns a store with plaintext sharedSecrets.
     * The underlying file stores encrypted secrets; the plaintext versions
     * are cached in memory and only re-read from disk on cache miss.
     */
    private fun loadStore(): TrustStore {
        cachedStore?.let { return it }

        // Clean up orphaned temp file from prior crash
        if (tmpFile.exists()) {
            Log.withTag("DeviceTrustStore").w { "Cleaning orphaned temp file from prior save" }
            tmpFile.delete()
        }

        if (!file.exists()) {
            val salt = generateSalt()
            currentSalt = salt
            val store = TrustStore(salt = salt)
            cachedStore = store
            return store
        }
        return try {
            val raw = json.decodeFromString<TrustStore>(file.readText())
            val storeSalt = raw.salt.ifBlank { generateSalt() }

            // Decrypt each peer's sharedSecret to plaintext in memory
            val decryptedPeers = raw.peers.map { peer ->
                val secret = peer.sharedSecret
                if (secret.isEmpty()) return@map peer
                decryptToPlaintext(secret, storeSalt)?.let { peer.copy(sharedSecret = it) } ?: peer
            }
            val decrypted = raw.copy(salt = storeSalt, peers = decryptedPeers)

            // If the store was pre-PBKDF2 (empty salt), upgrade immediately
            if (raw.salt.isBlank()) {
                Log.withTag("DeviceTrustStore").i { "Upgrading trust store to PBKDF2 encryption" }
                currentSalt = storeSalt
                cachedStore = decrypted
                // Write encrypted version with the new salt before returning
                file.writeText(json.encodeToString(encryptStore(decrypted)))
                return decrypted
            }

            currentSalt = storeSalt
            cachedStore = decrypted
            decrypted
        } catch (e: Exception) {
            Log.withTag("DeviceTrustStore").e(e) { "Corrupt trust store at ${file.absolutePath}, resetting: ${e.message}" }
            val salt = generateSalt()
            currentSalt = salt
            val store = TrustStore(salt = salt)
            cachedStore = store
            store
        }
    }

    private fun saveStore(store: TrustStore) {
        cachedStore = store
        currentSalt = store.salt.ifBlank { generateSalt() }
        file.parentFile.mkdirs()
        val encrypted = encryptStore(store.copy(salt = currentSalt!!))
        // Atomic write: write to .tmp, then rename
        val text = json.encodeToString(encrypted)
        tmpFile.writeText(text)
        if (!tmpFile.renameTo(file)) {
            // renameTo can fail on Windows if target exists and is locked
            file.writeText(text)
        }
    }

    /** Encrypt every peer's sharedSecret using the store's salt. */
    private fun encryptStore(store: TrustStore): TrustStore {
        val salt = store.salt.ifBlank { currentSalt ?: generateSalt() }
        val key = deriveKey(salt)
        return store.copy(
            salt = salt,
            peers = store.peers.map { it.copy(sharedSecret = encrypt(key, it.sharedSecret)) }
        )
    }

    /**
     * Try to decrypt a secret to plaintext. Attempts PBKDF2 first,
     * falls back to legacy SHA-256, then falls back to treating it as
     * already plaintext (pre-encryption migration).
     *
     * @return decrypted plaintext, or null if already plaintext
     */
    private fun decryptToPlaintext(encrypted: String, storeSalt: String): String? {
        // If not valid Base64, it's already plaintext (legacy, pre-encryption)
        val raw = try { Base64.getDecoder().decode(encrypted) } catch (_: Exception) { return null }
        if (raw.size < 13) return null  // GCM IV (12) + tag (min 1) = at least 13 bytes

        // Try PBKDF2-derived key first
        if (storeSalt.isNotBlank()) {
            try {
                return decrypt(deriveKey(storeSalt), encrypted)
            } catch (_: Exception) { /* fall through */ }
        }

        // Try legacy SHA-256-derived key
        try {
            return decrypt(oldDeriveKey(), encrypted)
        } catch (_: Exception) { /* fall through */ }

        // Not decryptable — return null, caller keeps plaintext as-is
        return null
    }

    // ---- PBKDF2 key derivation ----

    companion object {
        /**
         * PBKDF2 iterations for key derivation.
         * Production: 100,000  (strong, takes ~10ms per operation).
         * Tests may override via setProperty or env var.
         */
        var pbkdf2Iterations: Int = System.getProperty("nepenthe.pbkdf2.iterations")?.toIntOrNull()
            ?: 100_000

        private const val KEY_LENGTH = 256

        private fun generateSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun deriveKey(salt: String): SecretKey {
            val spec: KeySpec = PBEKeySpec(
                "nepenthe-truststore-v2".toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                pbkdf2Iterations,
                KEY_LENGTH
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        }

        /** Legacy key derivation (SHA-256 of user.home + os.name + salt). */
        private fun oldDeriveKey(): SecretKey {
            val seed = System.getProperty("user.home", "unknown") +
                       System.getProperty("os.name", "unknown")
            val md = MessageDigest.getInstance("SHA-256")
            md.update(seed.toByteArray())
            md.update("::nepenthe-truststore-key::".toByteArray())
            return SecretKeySpec(md.digest(), "AES")
        }

        private fun encrypt(key: SecretKey, plaintext: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = iv + ciphertext
            return Base64.getEncoder().encodeToString(combined)
        }

        private fun decrypt(key: SecretKey, encrypted: String): String {
            val combined = Base64.getDecoder().decode(encrypted)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }
}
