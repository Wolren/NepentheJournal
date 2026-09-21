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
 * iOS random 256 bit device identity persisted in the app sandbox.
 *
 * The old identity derived the deviceId from
 * platformDeviceName().hashCode(), which is guessable by anyone on the LAN
 * who can see the device name from mDNS. This store generates 32 random
 * bytes once (via the platform CSPRNG [secureRandomBytes]), persists them
 * encrypted at rest (AES-256-GCM under a Keychain-wrapped key, same layout
 * as the trust store), and derives a stable deviceId plus a SHA-256
 * fingerprint from the secret bytes. The fingerprint binds the identity
 * the same way the JVM TLS cert fingerprint does, without needing platform
 * TLS certs.
 *
 * Fail closed: a corrupt or undecryptable file throws instead of minting a
 * fresh identity (which would change the deviceId and orphan every pairing).
 * Plaintext files from older installs migrate and are re-encrypted on save.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceIdentityStore(private val dataDir: String) {

    @Serializable
    private data class IosIdentityFile(
        val version: Int = 2,
        val identityHex: String = ""
    )

    private val iosSyncLock = PlatformLock()
    private val fileManager = NSFileManager.defaultManager
    private val filePath: String get() = "$dataDir/ios-device-identity.json"
    private val backupPath: String get() = "$dataDir/ios-device-identity.json.bak"
    private val fileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val atRestKey = IosAtRestKey("journal.device-identity")

    @kotlin.concurrent.Volatile
    private var cachedHex: String? = null

    /** 32 random bytes identifying this device, generated once and persisted. */
    fun identityBytes(): ByteArray = iosSyncLock.withLock {
        cachedHex?.let { return hexToBytes(it) }
        if (fileManager.fileExistsAtPath(filePath)) {
            val stored = loadLocked().identityHex
            if (stored.length == 64 && stored.all { it in HEX }) {
                cachedHex = stored
                return hexToBytes(stored)
            }
            throw IllegalStateException("Device identity file is corrupt; refusing to reissue identity")
        }
        val fresh = secureRandomBytes(32)
        val hex = fresh.toHex()
        saveLocked(IosIdentityFile(identityHex = hex))
        cachedHex = hex
        fresh
    }

    /** Stable short device id derived from the secret identity bytes. */
    fun deviceId(): String {
        val digest = sha256(identityBytes())
        return "ios-" + digest.toHex().take(16)
    }

    /** Public fingerprint: SHA-256 hex of the secret identity bytes. */
    fun fingerprint(): String = sha256(identityBytes()).toHex()

    @Throws(IllegalStateException::class)
    private fun loadLocked(): IosIdentityFile {
        val size = (fileManager.attributesOfItemAtPath(filePath, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0
        if (size > MAX_IDENTITY_FILE_BYTES) {
            throw IllegalStateException("Device identity file too large ($size bytes)")
        }
        val text = try {
            PlatformFile.readText(filePath)
        } catch (e: Exception) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                Log.withTag("IosIdentity").w { "Identity file unreadable; trying backup" }
                return readAndDecrypt(backupPath)
            }
            throw IllegalStateException("Device identity unreadable and no backup available", e)
        }
        if (text.isBlank()) throw IllegalStateException("Device identity file is empty")
        runCatching { readAndDecryptText(text) }.getOrNull()?.let { return it }
        if (fileManager.fileExistsAtPath(backupPath)) {
            Log.withTag("IosIdentity").w { "Identity file undecryptable; trying backup" }
            runCatching { readAndDecrypt(backupPath) }.getOrNull()?.let { return it }
        }
        throw IllegalStateException("Device identity undecryptable; refusing to reissue identity")
    }

    /** Decrypt [text] (or parse legacy plaintext) into the identity file. Throws on failure. */
    @Throws(IllegalStateException::class)
    private fun readAndDecryptText(text: String): IosIdentityFile {
        val key = atRestKey.loadOrNull()
        if (key != null) {
            runCatching {
                val payload = base64Decode(text.trim())
                val fileText = decryptBody(payload, key)
                fileJson.decodeFromString(IosIdentityFile.serializer(), fileText)
            }.getOrNull()?.let { return it }
        }
        runCatching {
            fileJson.decodeFromString(IosIdentityFile.serializer(), text)
        }.getOrNull()?.let { return it }
        throw IllegalStateException("Identity payload is neither decryptable nor legacy plaintext")
    }

    @Throws(IllegalStateException::class)
    private fun readAndDecrypt(path: String): IosIdentityFile {
        val size = (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0
        if (size > MAX_IDENTITY_FILE_BYTES) {
            throw IllegalStateException("Device identity backup too large ($size bytes)")
        }
        return readAndDecryptText(PlatformFile.readText(path))
    }

    private fun saveLocked(file: IosIdentityFile) {
        val key = atRestKey.loadOrCreate()
        val payload = base64Encode(encryptBody(fileJson.encodeToString(file), key))
        if (fileManager.fileExistsAtPath(filePath)) {
            if (fileManager.fileExistsAtPath(backupPath)) {
                fileManager.removeItemAtPath(backupPath, null)
            }
            if (!fileManager.copyItemAtPath(filePath, toPath = backupPath, error = null)) {
                Log.withTag("IosIdentity").w { "Cannot write identity backup" }
            }
            fileManager.removeItemAtPath(filePath, null)
        }
        PlatformFile.writeText(filePath, payload)
    }

    companion object {
        /** Cap so a corrupt file can never OOM the reader. */
        const val MAX_IDENTITY_FILE_BYTES = 1L * 1024 * 1024

        private const val HEX = "0123456789abcdef"

        fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v shr 4])
                out.append(HEX[v and 0xF])
            }
            return out.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = HEX.indexOf(hex[i * 2])
                val lo = HEX.indexOf(hex[i * 2 + 1])
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}

/** Current epoch millis on every platform via the shared TimeProvider. */
fun iosNowMillis(): Long = currentTimeMillis()
