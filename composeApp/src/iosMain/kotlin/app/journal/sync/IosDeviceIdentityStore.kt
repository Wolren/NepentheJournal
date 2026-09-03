package app.journal.sync

import app.journal.util.PlatformFile
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * iOS random 256 bit device identity persisted in the app sandbox.
 *
 * The old identity derived the deviceId from
 * platformDeviceName().hashCode(), which is guessable by anyone on the LAN
 * who can see the device name from mDNS. This store generates 32 random
 * bytes once (via the platform CSPRNG [secureRandomBytes]), persists them
 * as hex, and derives a stable deviceId plus a SHA-256 fingerprint from
 * the secret bytes. The fingerprint binds the identity the same way the
 * JVM TLS cert fingerprint does, without needing platform TLS certs.
 */
class IosDeviceIdentityStore(private val dataDir: String) {

    @Serializable
    private data class IosIdentityFile(
        val version: Int = 1,
        val identityHex: String = ""
    )

    private val iosSyncLock = PlatformLock()
    private val filePath: String get() = "$dataDir/ios-device-identity.json"
    private val fileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @kotlin.concurrent.Volatile
    private var cachedHex: String? = null

    /** 32 random bytes identifying this device, generated once and persisted. */
    fun identityBytes(): ByteArray = iosSyncLock.withLock {
        cachedHex?.let { return hexToBytes(it) }
        val stored = loadLocked().identityHex
        if (stored.length == 64 && stored.all { it in HEX }) {
            cachedHex = stored
            return hexToBytes(stored)
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

    private fun loadLocked(): IosIdentityFile {
        return try {
            val text = PlatformFile.readText(filePath)
            if (text.isBlank()) IosIdentityFile() else fileJson.decodeFromString(IosIdentityFile.serializer(), text)
        } catch (_: Exception) {
            IosIdentityFile()
        }
    }

    private fun saveLocked(file: IosIdentityFile) {
        try {
            PlatformFile.writeText(filePath, fileJson.encodeToString(file))
        } catch (_: Exception) {
        }
    }

    companion object {
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
