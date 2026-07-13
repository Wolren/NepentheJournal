package app.journal.sync

import app.journal.model.SyncConfig
import kotlinx.coroutines.flow.Flow

/**
 * P2P Sync orchestration with support for:
 * - HTTP bulk push/pull (initial sync)
 * - WebSocket continuous sync (real-time mutation push)
 * - LAN peer discovery via mDNS / NSD
 * - Manual IP pairing
 */
interface SyncEngine {
    suspend fun startHosting(config: SyncConfig): Result<HostingInfo>
    suspend fun stopHosting()

    /** Bulk HTTP sync: push local changes since [since], pull remote changes. */
    suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean = false): Result<Unit>

    /**
     * Establish a persistent WebSocket connection for continuous sync.
     * After the initial bulk exchange, mutations are pushed in real-time
     * over the WebSocket. Reconnects automatically on drop.
     */
    suspend fun startContinuousSync(peer: DiscoveredPeer)

    /** Disconnect a continuous sync session. */
    suspend fun stopContinuousSync(deviceId: String)

    /** Disconnect from a peer (both HTTP and WebSocket). */
    suspend fun disconnectFrom(deviceId: String)

    /** Revoke a previously paired device. */
    suspend fun revokeTrustedDevice(deviceId: String) {}

    /** List trusted paired devices. */
    fun trustedDevices(): List<TrustedDeviceInfo> = emptyList()

    /** Start LAN discovery for nearby devices. */
    fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent>

    /** Stop LAN discovery. */
    suspend fun stopDiscovery()

    /** Connect to a manually entered IP:port. */
    suspend fun connectManually(host: String, port: Int, token: String?): Result<Unit>

    /** Observe overall sync status. */
    fun observeStatus(): Flow<SyncStatusSnapshot>
}

data class TrustedDeviceInfo(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val pairedAt: Long,
    val lastSeenAt: Long?
)

data class HostingInfo(val address: String, val port: Int, val fingerprint: String)

data class SyncStatusSnapshot(
    val isHosting: Boolean,
    val hostAddress: String?,
    val activeConnections: List<ConnectedPeer>,
    val lastSyncAt: Long?,
    val pendingConflicts: Int,
    val lastError: String?,
    val pairingToken: String? = null,
    val pairedDeviceCount: Int = 0,
    val continuousPeers: Int = 0
)

data class ConnectedPeer(val deviceId: String, val displayName: String, val direction: SyncDirection)
enum class SyncDirection { PUSH_PULL, PUSH_ONLY, PULL_ONLY }
enum class DiscoveryMode { MANUAL, LAN_AUTO_DISCOVERY, HYBRID }

data class DiscoveredPeer(
    val deviceId: String?,
    val displayName: String,
    val host: String,
    val port: Int,
    val isTrusted: Boolean,
    val fingerprint: String?,
    val pairingToken: String? = null
)

sealed class LanDiscoveryEvent {
    data class PeerFound(val peer: DiscoveredPeer) : LanDiscoveryEvent()
    data class PeerLost(val deviceId: String) : LanDiscoveryEvent()
    data class DiscoveryError(val reason: String) : LanDiscoveryEvent()
}
