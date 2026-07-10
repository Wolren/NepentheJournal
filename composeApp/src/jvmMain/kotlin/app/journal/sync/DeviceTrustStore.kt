package app.journal.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the set of trusted devices that have completed pairing.
 *
 * Stored as JSON at ~/.psychonautica/trusted-devices.json.
 * Each record contains the device's fingerprint (SHA-256 of TLS cert),
 * a display name, and the shared HMAC secret exchanged during pairing.
 */
class DeviceTrustStore(private val dataDir: String = defaultDataDir()) {

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
        val version: Int = 1,
        val peers: List<TrustedPeer> = emptyList()
    )

    private val file: File get() = File(dataDir, "trusted-devices.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun addPeer(peer: TrustedPeer) {
        val store = loadStore()
        val updated = store.peers.filter { it.deviceId != peer.deviceId } + peer
        saveStore(store.copy(peers = updated))
    }

    fun updateLastSeen(deviceId: String, at: Long = System.currentTimeMillis()) {
        val store = loadStore()
        val updated = store.peers.map {
            if (it.deviceId == deviceId) it.copy(lastSeenAt = at) else it
        }
        saveStore(store.copy(peers = updated))
    }

    fun isTrusted(fingerprint: String): Boolean =
        loadStore().peers.any { it.fingerprint == fingerprint }

    fun isTrustedDeviceId(deviceId: String): Boolean =
        loadStore().peers.any { it.deviceId == deviceId }

    fun getSharedSecret(deviceId: String): String? =
        loadStore().peers.find { it.deviceId == deviceId }?.sharedSecret

    fun getPeer(fingerprint: String): TrustedPeer? =
        loadStore().peers.find { it.fingerprint == fingerprint }

    fun getPeerById(deviceId: String): TrustedPeer? =
        loadStore().peers.find { it.deviceId == deviceId }

    fun listPeers(): List<TrustedPeer> = loadStore().peers

    fun revokeDevice(deviceId: String) {
        val store = loadStore()
        saveStore(store.copy(peers = store.peers.filter { it.deviceId != deviceId }))
    }

    fun clearAll() {
        saveStore(TrustStore())
    }

    fun count(): Int = loadStore().peers.size

    // ---- Private ----

    private fun loadStore(): TrustStore {
        if (!file.exists()) return TrustStore()
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            System.err.println("Corrupt trust store, resetting: ${e.message}")
            TrustStore()
        }
    }

    private fun saveStore(store: TrustStore) {
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(store))
    }

    companion object {
        fun defaultDataDir(): String {
            val home = System.getProperty("user.home") ?: "."
            return "$home${File.separator}.psychonautica"
        }
    }
}
