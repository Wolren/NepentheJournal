package app.journal.sync

import app.journal.data.AppJson
import app.journal.log.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
    private val backupFile: File get() = File(dataDir, "trusted-devices.json.bak")
    private val tmpFile: File get() = File(dataDir, "trusted-devices.json.tmp")
    private val json get() = AppJson.json
    override fun toString(): String = json.encodeToString(this)

    /**
     * At-rest encryption key source. Prefers the random key file
     * (dataDir/at-rest.key, user-only permissions); falls back to the
     * legacy PBKDF2 constant-password derivation when the key file cannot
     * be created (migration path; the store is re-encrypted on next save).
     */
    private val atRestKey = AtRestKey(dataDir)

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
     * Load the trust store from disk (or cache).
     *
     * Fail-closed contract: a corrupt or unreadable file NEVER resets to an
     * empty store (that would silently unpair every device and let a fresh
     * pairing overwrite existing trust). Instead the loader retries once,
     * then falls back to the backup copy, and only throws when neither is
     * usable. The in-memory cache is left untouched on failure so the last
     * known trust set keeps working for the rest of the session.
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
            if (backupFile.exists()) {
                Log.withTag("DeviceTrustStore").w { "Primary trust store missing; restoring from backup" }
                return try {
                    val restored = readAndDecrypt(backupFile)
                    cachedStore = restored
                    currentSalt = restored.salt
                    restored
                } catch (e: Exception) {
                    Log.withTag("DeviceTrustStore").e(e) { "Backup trust store unreadable" }
                    throw IllegalStateException("Trust store missing and backup unreadable", e)
                }
            }
            val salt = generateSalt()
            currentSalt = salt
            val store = TrustStore(salt = salt)
            cachedStore = store
            return store
        }
        return try {
            val decrypted = readAndDecrypt(file)
            // If the store was pre-PBKDF2 (empty salt), upgrade immediately
            if (decrypted.salt.isBlank()) {
                Log.withTag("DeviceTrustStore").i { "Upgrading trust store to key-file encryption" }
                val upgraded = decrypted.copy(salt = generateSalt())
                currentSalt = upgraded.salt
                cachedStore = upgraded
                writeEncrypted(upgraded)
                return upgraded
            }
            currentSalt = decrypted.salt
            cachedStore = decrypted
            decrypted
        } catch (e: Exception) {
            // Retry once (transient IO), then try the backup. Never reset.
            Log.withTag("DeviceTrustStore").w { "Trust store read failed (${e.message}); retrying" }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) { }
            try {
                val decrypted = readAndDecrypt(file)
                currentSalt = decrypted.salt
                cachedStore = decrypted
                return decrypted
            } catch (retry: Exception) {
                Log.withTag("DeviceTrustStore").e(retry) { "Trust store retry failed; trying backup" }
            }
            if (backupFile.exists()) {
                try {
                    val restored = readAndDecrypt(backupFile)
                    Log.withTag("DeviceTrustStore").w { "Restored trust store from backup copy" }
                    cachedStore = restored
                    currentSalt = restored.salt
                    return restored
                } catch (backupErr: Exception) {
                    Log.withTag("DeviceTrustStore").e(backupErr) { "Backup trust store unreadable; failing closed" }
                }
            } else {
                Log.withTag("DeviceTrustStore").e(e) { "Corrupt trust store at ${file.absolutePath}; failing closed (no backup)" }
            }
            cachedStore?.let { return it }
            throw IllegalStateException("Trust store unreadable and no cached copy available", e)
        }
    }

    /**
     * Read [source] with a file-length precheck, decode it, and decrypt each
     * peer secret to plaintext. Throws on any failure.
     */
    private fun readAndDecrypt(source: File): TrustStore {
        if (source.length() > MAX_TRUST_FILE_BYTES) {
            throw IllegalStateException("Trust store file too large (${source.length()} bytes)")
        }
        val raw = json.decodeFromString<TrustStore>(source.readText())
        val storeSalt = raw.salt.ifBlank { generateSalt() }
        val decryptedPeers = raw.peers.map { peer ->
            val secret = peer.sharedSecret
            if (secret.isEmpty()) return@map peer
            peer.copy(sharedSecret = decryptToPlaintext(secret))
        }
        return raw.copy(salt = storeSalt, peers = decryptedPeers)
    }

    /** Encrypt [store] and write it atomically, keeping the prior file as backup. */
    private fun writeEncrypted(store: TrustStore) {
        file.parentFile.mkdirs()
        val encrypted = encryptStore(store)
        val text = json.encodeToString(encrypted)
        tmpFile.writeText(text)
        if (file.exists()) {
            try {
                file.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Log.withTag("DeviceTrustStore").w { "Cannot write trust store backup (${e.message})" }
            }
        }
        if (!tmpFile.renameTo(file)) {
            // renameTo can fail on Windows if target exists and is locked
            file.writeText(text)
        }
    }

    private fun saveStore(store: TrustStore) {
        val salted = store.copy(salt = store.salt.ifBlank { currentSalt ?: generateSalt() })
        cachedStore = salted
        currentSalt = salted.salt
        writeEncrypted(salted)
    }

    /** Encrypt every peer's sharedSecret using the store's salt. */
    private fun encryptStore(store: TrustStore): TrustStore {
        val salt = store.salt.ifBlank { currentSalt ?: generateSalt() }
        val key = encryptionKey(salt)
        return store.copy(
            salt = salt,
            peers = store.peers.map { it.copy(sharedSecret = encrypt(key, it.sharedSecret)) }
        )
    }

    /**
     * The key used to encrypt the store on disk: the random at-rest key file.
     * Key-file-only writer: when the key file cannot be created (ACL cannot
     * be hardened), creation fails closed instead of silently falling back to
     * a derivable constant password.
     */
    private fun encryptionKey(salt: String): SecretKey {
        atRestKey.loadOrNull()?.let { return SecretKeySpec(it, "AES") }
        // create() throws when the key file cannot be stored securely.
        return SecretKeySpec(atRestKey.create(), "AES")
    }

    /**
     * Decrypt a stored secret to plaintext with the at-rest key file.
     * Fail closed: anything that does not decrypt under the current scheme
     * throws, and the store load aborts. The legacy PBKDF2-constant and
     * SHA-256 fallbacks plus the plaintext passthrough were removed after
     * the migration release: they let anyone with file read plus source
     * knowledge recover every secret, which defeats at-rest encryption.
     */
    private fun decryptToPlaintext(encrypted: String): String {
        // Non-Base64 content is not a ciphertext of this scheme: fail closed.
        try {
            Base64.getDecoder().decode(encrypted)
        } catch (_: Exception) {
            throw IllegalStateException("Trust store entry is not valid ciphertext")
        }
        atRestKey.loadOrNull()?.let { key ->
            try {
                return decrypt(SecretKeySpec(key, "AES"), encrypted)
            } catch (e: Exception) {
                throw IllegalStateException("Trust store entry failed to decrypt", e)
            }
        }
        throw IllegalStateException("No at-rest key available to decrypt trust store")
    }

    companion object {
        /** Cap on the trust-store file so a corrupt file can never OOM the reader. */
        const val MAX_TRUST_FILE_BYTES = 10L * 1024 * 1024

        private fun generateSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
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
