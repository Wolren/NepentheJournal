package app.journal.sync

import app.journal.log.Log
import app.journal.util.PlatformFile
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSFileSize

/**
 * iOS persistent per device trust store.
 *
 * Mirrors the JVM DeviceTrustStore contract on the subset the iOS transport
 * needs: lookup of a caller device shared secret, add, revoke, last seen.
 *
 * At rest, the JSON payload is AES-256-GCM encrypted with a random
 * Keychain-wrapped key ([IosAtRestKey]) and stored as base64 of
 * nonce || ciphertext || tag (the [encryptBody] layout). Writes are atomic
 * with a backup copy, reads are size-capped, and the last known good store
 * is cached in memory.
 *
 * Fail-closed contract: a corrupt or undecryptable file NEVER resets to an
 * empty store (that would silently unpair every device and let a fresh
 * pairing overwrite existing trust). Loads throw, and the in-memory cache
 * keeps serving. Plaintext files from older installs are migrated: they
 * parse as before and are re-encrypted on the next save.
 *
 * Per device records replace the old single global pairing secret: every
 * authenticated request looks up the caller deviceId, unknown callers are
 * rejected, and the push batch deviceId must equal the authenticated caller.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceTrustStore(private val dataDir: String) {

    @Serializable
    data class IosTrustedPeer(
        val deviceId: String,
        val displayName: String,
        val fingerprint: String,
        val sharedSecret: String,
        val pairedAt: Long,
        val lastSeenAt: Long? = null
    )

    @Serializable
    private data class IosTrustFile(
        val version: Int = 2,
        val peers: List<IosTrustedPeer> = emptyList()
    )

    private val iosSyncLock = PlatformLock()
    private val fileManager = NSFileManager.defaultManager
    private val filePath: String get() = "$dataDir/ios-trusted-devices.json"
    private val backupPath: String get() = "$dataDir/ios-trusted-devices.json.bak"
    private val tmpPath: String get() = "$dataDir/ios-trusted-devices.json.tmp"
    private val fileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val atRestKey = IosAtRestKey("journal.trust-store")

    /** In-memory cache of the last known good store; keeps serving when disk reads fail. */
    @kotlin.concurrent.Volatile
    private var cachedStore: IosTrustFile? = null

    fun addPeer(peer: IosTrustedPeer) = iosSyncLock.withLock {
        val store = loadLocked()
        val updated = store.copy(peers = store.peers.filter { it.deviceId != peer.deviceId } + peer)
        saveLocked(updated)
    }

    fun getSharedSecret(deviceId: String): String? = iosSyncLock.withLock {
        loadLocked().peers.find { it.deviceId == deviceId }?.sharedSecret
    }

    fun isTrustedDeviceId(deviceId: String): Boolean = iosSyncLock.withLock {
        loadLocked().peers.any { it.deviceId == deviceId }
    }

    fun getPeerById(deviceId: String): IosTrustedPeer? = iosSyncLock.withLock {
        loadLocked().peers.find { it.deviceId == deviceId }
    }

    fun updateLastSeen(deviceId: String, at: Long = currentTimeMillis()) = iosSyncLock.withLock {
        val store = loadLocked()
        saveLocked(store.copy(peers = store.peers.map {
            if (it.deviceId == deviceId) it.copy(lastSeenAt = at) else it
        }))
    }

    fun revokeDevice(deviceId: String) = iosSyncLock.withLock {
        val store = loadLocked()
        saveLocked(store.copy(peers = store.peers.filter { it.deviceId != deviceId }))
    }

    fun listPeers(): List<IosTrustedPeer> = iosSyncLock.withLock {
        loadLocked().peers.toList()
    }

    fun count(): Int = iosSyncLock.withLock {
        loadLocked().peers.size
    }

    fun clearAll() = iosSyncLock.withLock {
        saveLocked(IosTrustFile())
    }

    private fun loadLocked(): IosTrustFile {
        if (!fileManager.fileExistsAtPath(filePath)) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                Log.withTag("IosTrustStore").w { "Primary trust store missing; restoring from backup" }
                return try {
                    val restored = readAndDecrypt(backupPath)
                    cachedStore = restored
                    restored
                } catch (e: Exception) {
                    Log.withTag("IosTrustStore").e(e) { "Backup trust store unreadable" }
                    throw IllegalStateException("Trust store missing and backup unreadable", e)
                }
            }
            val fresh = IosTrustFile()
            cachedStore = fresh
            return fresh
        }
        return try {
            val store = readAndDecrypt(filePath)
            cachedStore = store
            store
        } catch (e: Exception) {
            Log.withTag("IosTrustStore").w { "Trust store read failed (${e.message}); trying backup" }
            if (fileManager.fileExistsAtPath(backupPath)) {
                try {
                    val restored = readAndDecrypt(backupPath)
                    Log.withTag("IosTrustStore").w { "Restored trust store from backup copy" }
                    cachedStore = restored
                    return restored
                } catch (backupErr: Exception) {
                    Log.withTag("IosTrustStore").e(backupErr) { "Backup trust store unreadable; failing closed" }
                }
            } else {
                Log.withTag("IosTrustStore").e(e) { "Trust store unreadable at $filePath; failing closed (no backup)" }
            }
            cachedStore?.let { return it }
            throw IllegalStateException("Trust store unreadable and no cached copy available", e)
        }
    }

    /**
     * Read [path] with a file-length precheck and decrypt it. Plaintext
     * JSON (pre-encryption installs) parses directly as a migration path.
     * Throws on any failure.
     */
    @Throws(IllegalStateException::class)
    private fun readAndDecrypt(path: String): IosTrustFile {
        val size = (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0
        if (size > MAX_TRUST_FILE_BYTES) {
            throw IllegalStateException("Trust store file too large ($size bytes)")
        }
        val text = PlatformFile.readText(path)
        if (text.isBlank()) throw IllegalStateException("Trust store file is empty")
        // Encrypted layout first: base64 of nonce || ciphertext || tag.
        val key = atRestKey.loadOrNull()
        if (key != null) {
            runCatching {
                val payload = base64Decode(text.trim())
                val storeJson = decryptBody(payload, key)
                fileJson.decodeFromString(IosTrustFile.serializer(), storeJson)
            }.getOrNull()?.let { return it }
        }
        // Migration path: plaintext JSON from installs before at-rest encryption.
        runCatching {
            fileJson.decodeFromString(IosTrustFile.serializer(), text)
        }.getOrNull()?.let { return it }
        throw IllegalStateException("Trust store at $path is neither decryptable nor legacy plaintext")
    }

    private fun saveLocked(store: IosTrustFile) {
        cachedStore = store
        val key = atRestKey.loadOrCreate()
        val storeJson = fileJson.encodeToString(store)
        val encrypted = base64Encode(encryptBody(storeJson, key))
        if (encrypted.length > MAX_TRUST_FILE_BYTES) {
            throw IllegalStateException("Encrypted trust store too large, refusing to save")
        }
        if (fileManager.fileExistsAtPath(tmpPath)) {
            fileManager.removeItemAtPath(tmpPath, null)
        }
        PlatformFile.writeText(tmpPath, encrypted)
        if (fileManager.fileExistsAtPath(filePath)) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                fileManager.removeItemAtPath(backupPath, null)
            }
            if (!fileManager.copyItemAtPath(filePath, toPath = backupPath, error = null)) {
                Log.withTag("IosTrustStore").w { "Cannot write trust store backup" }
            }
            fileManager.removeItemAtPath(filePath, null)
        }
        if (!fileManager.moveItemAtPath(tmpPath, toPath = filePath, error = null)) {
            PlatformFile.writeText(filePath, encrypted)
            fileManager.removeItemAtPath(tmpPath, null)
        }
    }

    companion object {
        /** Cap on the trust-store file so a corrupt file can never OOM the reader. */
        const val MAX_TRUST_FILE_BYTES = 10L * 1024 * 1024
    }
}
