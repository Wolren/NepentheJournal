package app.journal.sync

import app.journal.util.PlatformFile
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * iOS persistent per device trust store.
 *
 * Mirrors the JVM DeviceTrustStore contract on the subset the iOS transport
 * needs: lookup of a caller device shared secret, add, revoke, last seen.
 * Secrets are stored as hex in a JSON file inside the app sandbox
 * ([dataDir]/ios-trusted-devices.json). The sandbox is app private on iOS,
 * which is the platform equivalent of the user only file permissions used
 * on desktop. File access is serialized with a simple monitor lock.
 *
 * Per device records replace the old single global pairing secret: every
 * authenticated request looks up the caller deviceId, unknown callers are
 * rejected, and the push batch deviceId must equal the authenticated caller.
 */
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
        val version: Int = 1,
        val peers: List<IosTrustedPeer> = emptyList()
    )

    private val iosSyncLock = PlatformLock()
    private val filePath: String get() = "$dataDir/ios-trusted-devices.json"
    private val fileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        return try {
            val text = PlatformFile.readText(filePath)
            if (text.isBlank()) IosTrustFile() else fileJson.decodeFromString(IosTrustFile.serializer(), text)
        } catch (_: Exception) {
            IosTrustFile()
        }
    }

    private fun saveLocked(store: IosTrustFile) {
        try {
            PlatformFile.writeText(filePath, fileJson.encodeToString(store))
        } catch (_: Exception) {
        }
    }
}
