package app.journal.sync

import app.journal.log.Log
import app.journal.util.PlatformFile
import app.journal.util.PlatformLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSFileManager

/**
 * The host's STATIC P-256 ECDH keypair (contract section g).
 *
 * Generated once on first host start and persisted beside the device
 * identity material, handled exactly like [IosDeviceIdentityStore] handles
 * its key bytes: a JSON file in the sync data dir, AES-256-GCM encrypted at
 * rest under a Keychain-wrapped [IosAtRestKey], rotated to `.bak` on every
 * rewrite, and FAIL CLOSED on a corrupt or undecryptable file (reissuing
 * would silently change the advertised public key). There is no legacy
 * plaintext era for this file, but a decode fallback is kept so the loader
 * shape mirrors the identity store.
 *
 * The stored value is the Security framework private-key external
 * representation produced by SecKeyCopyExternalRepresentation, restored with
 * SecKeyCreateWithData semantics (see [IosEcdh.importPrivateKey]). Key
 * material is never logged.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEcdhIdentityStore(private val dataDir: String) {

    @Serializable
    private data class IosEcdhKeyFile(
        val version: Int = 1,
        val privateKeyB64: String = ""
    )

    private val lock = PlatformLock()
    private val fileManager = NSFileManager.defaultManager
    private val filePath: String get() = "$dataDir/ios-ecdh-key.json"
    private val backupPath: String get() = "$dataDir/ios-ecdh-key.json.bak"
    private val fileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val atRestKey = IosAtRestKey("journal.ecdh-identity")

    @kotlin.concurrent.Volatile
    private var cachedPrivateKeyB64: String? = null

    @kotlin.concurrent.Volatile
    private var cachedPublicKeyB64: String? = null

    /**
     * Base64 uncompressed point (0x04 || X || Y) of the STATIC host key.
     * Generates and persists the keypair on first call (first host start).
     * Throws when the stored key is unusable; callers fail closed.
     */
    fun publicKeyB64(): String = lock.withLock {
        val cached = cachedPublicKeyB64
        if (cached != null) {
            cached
        } else {
            val privateKey = IosEcdh.importPrivateKey(base64Decode(loadOrCreatePrivateKeyB64Locked()))
                ?: throw IllegalStateException(
                    "Stored host ECDH private key is unusable; refusing to reissue the host key"
                )
            val exported = try {
                IosEcdh.exportPublicKeyB64(privateKey)
            } finally {
                CFRelease(privateKey)
            }
            val publicKey = exported
                ?: throw IllegalStateException("Stored host ECDH private key cannot produce a public key")
            cachedPublicKeyB64 = publicKey
            publicKey
        }
    }

    /**
     * ECDH between the STATIC host private key and [peerPublicKeyBytes]
     * (the client's 65-byte uncompressed point). Returns null when the peer
     * point is malformed or NOT on the curve (SecKeyCreateWithData rejects
     * it), or when the exchange itself fails; the caller then fails the
     * pairing closed. Never logs key material.
     */
    fun agreeSharedSecret(peerPublicKeyBytes: ByteArray): ByteArray? = lock.withLock {
        val privateKey = IosEcdh.importPrivateKey(base64Decode(loadOrCreatePrivateKeyB64Locked()))
            ?: return@withLock null
        try {
            IosEcdh.agreeRaw(privateKey, peerPublicKeyBytes)
        } finally {
            CFRelease(privateKey)
        }
    }

    /** Load the stored private key (base64), generating and saving it once. */
    private fun loadOrCreatePrivateKeyB64Locked(): String {
        cachedPrivateKeyB64?.let { return it }
        if (!fileManager.fileExistsAtPath(filePath)) {
            // First host start: generate the static pair and persist it.
            val privateKey = IosEcdh.generateKey()
                ?: throw IllegalStateException("Could not generate the host ECDH key pair")
            val exportedExternal = try {
                IosEcdh.exportPrivateKeyB64(privateKey)
            } finally {
                CFRelease(privateKey)
            }
            val external = exportedExternal
                ?: throw IllegalStateException("Could not export the host ECDH private key")
            saveLocked(IosEcdhKeyFile(privateKeyB64 = external))
            cachedPrivateKeyB64 = external
            return external
        }
        val stored = loadLocked().privateKeyB64
        if (stored.isBlank()) {
            throw IllegalStateException("Host ECDH key file is empty; refusing to reissue the host key")
        }
        cachedPrivateKeyB64 = stored
        return stored
    }

    /** Decrypt and parse [text], accepting only this file's own format. Throws on failure. */
    @Throws(IllegalStateException::class)
    private fun readAndDecryptText(text: String): IosEcdhKeyFile {
        val key = atRestKey.loadOrNull()
        if (key != null) {
            runCatching {
                val payload = base64Decode(text.trim())
                val fileText = decryptBody(payload, key)
                fileJson.decodeFromString(IosEcdhKeyFile.serializer(), fileText)
            }.getOrNull()?.let { return it }
        }
        runCatching {
            fileJson.decodeFromString(IosEcdhKeyFile.serializer(), text)
        }.getOrNull()?.let { return it }
        throw IllegalStateException("Host ECDH key payload is neither decryptable nor valid JSON")
    }

    @Throws(IllegalStateException::class)
    private fun loadLocked(): IosEcdhKeyFile {
        val text = try {
            PlatformFile.readText(filePath)
        } catch (e: Exception) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                Log.withTag("IosEcdh").w { "ECDH key file unreadable; trying backup" }
                return readAndDecryptText(PlatformFile.readText(backupPath))
            }
            throw IllegalStateException("Host ECDH key unreadable and no backup available", e)
        }
        if (text.isBlank()) throw IllegalStateException("Host ECDH key file is empty")
        runCatching { readAndDecryptText(text) }.getOrNull()?.let { return it }
        if (fileManager.fileExistsAtPath(backupPath)) {
            Log.withTag("IosEcdh").w { "ECDH key file undecryptable; trying backup" }
            runCatching { readAndDecryptText(PlatformFile.readText(backupPath)) }.getOrNull()?.let { return it }
        }
        throw IllegalStateException("Host ECDH key undecryptable; refusing to reissue the host key")
    }

    private fun saveLocked(file: IosEcdhKeyFile) {
        val key = atRestKey.loadOrCreate()
        val payload = base64Encode(encryptBody(fileJson.encodeToString(file), key))
        if (fileManager.fileExistsAtPath(filePath)) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                fileManager.removeItemAtPath(backupPath, null)
            }
            if (!fileManager.copyItemAtPath(filePath, toPath = backupPath, error = null)) {
                Log.withTag("IosEcdh").w { "Cannot write ECDH key backup" }
            }
            fileManager.removeItemAtPath(filePath, null)
        }
        PlatformFile.writeText(filePath, payload)
    }
}
