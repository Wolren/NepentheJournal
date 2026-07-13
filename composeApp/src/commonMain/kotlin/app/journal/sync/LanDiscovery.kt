package app.journal.sync

import kotlinx.coroutines.flow.Flow

/**
 * LAN peer discovery using mDNS on desktop and NsdManager on Android.
 * Discovers _nepenthe._tcp services on the local network.
 *
 * Platform implementations:
 *   Desktop → JmDNS (https://github.com/jmdns/jmdns)
 *   Android → NsdManager (built-in Network Service Discovery)
 */
expect class LanDiscovery() {
    fun startDiscovery(): Flow<LanDiscoveryEvent>
    fun registerService(port: Int, deviceId: String, fingerprint: String)
    fun unregisterService()
    fun stop()
}
