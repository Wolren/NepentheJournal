package app.journal.sync

import app.journal.model.DiscoveryMode
import kotlinx.coroutines.flow.Flow

/**
 * LAN peer discovery. Platform implementations:
 *
 *   Android  → NsdManager (Network Service Discovery, built-in)
 *              Service type: "_psychonautica._tcp"
 *
 *   JVM/Desktop → JmDNS (https://github.com/jmdns/jmdns)
 *              Service type: "_psychonautica._tcp.local."
 *
 *   iOS/macOS → Network.framework NWBrowser / NWListener (Bonjour)
 *              Service type: "_psychonautica._tcp"
 *
 *   MANUAL mode → user supplies IP:port; no mDNS required.
 *
 * Only fingerprint-pinned trusted peers trigger automatic sync.
 * Unknown peers are offered for pairing only.
 */
data class DiscoveredPeer(
    val deviceId: String?,
    val displayName: String,
    val host: String,
    val port: Int,
    val isTrusted: Boolean,
    val fingerprint: String?
)

interface LanDiscoveryService {
    fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent>
    suspend fun stopDiscovery()
    suspend fun connectManually(host: String, port: Int): DiscoveredPeer
}

sealed class LanDiscoveryEvent {
    data class PeerFound(val peer: DiscoveredPeer) : LanDiscoveryEvent()
    data class PeerLost(val deviceId: String) : LanDiscoveryEvent()
    data class DiscoveryError(val reason: String) : LanDiscoveryEvent()
}
