package app.journal.sync

import app.journal.model.SyncConfig
import kotlinx.coroutines.flow.Flow

/**
 * P2P Sync orchestration. Real Couchbase Lite / Kotbase API patterns:
 *
 * HOST (URLEndpointListener. Enterprise Edition required):
 *   val listenerConfig = URLEndpointListenerConfiguration(
 *       collections = db.collections,
 *       port = config.listenerPort,     // 0 = auto-assign
 *       tlsIdentity = myNamedTlsIdentity,
 *       authenticator = ListenerCertificateAuthenticator { certs ->
 *           certs.any { isTrustedFingerprint(it.encoded.sha256hex()) }
 *       }
 *   )
 *   val listener = URLEndpointListener(listenerConfig)
 *   listener.start()
 *   // listener.urls[0] → wss://192.168.x.x:4984 to advertise via mDNS
 *
 * CLIENT (Replicator. Community Edition sufficient):
 *   val endpoint = URLEndpoint("wss://${peer.host}:${peer.port}/")
 *   val replConfig = ReplicatorConfiguration(endpoint)
 *       .addCollections(db.collections, CollectionConfiguration(
 *           conflictResolver = { conflict ->
 *               val dt = conflict.localDocument?.getString("docType") ?: ""
 *               ConflictPolicy.strategyFor(dt).resolve(conflict)
 *           },
 *           pushFilter = { doc, _ ->
 *               doc.getString("docType") != "syncConfig"  // never push device config
 *           }
 *       ))
 *       .apply {
 *           replicatorType = ReplicatorType.PUSH_AND_PULL
 *           isContinuous = config.continuousSync
 *           isAcceptOnlySelfSignedServerCertificate = true
 *           pinnedServerCertificate = loadPinnedCert(peer.fingerprint)
 *           enableDeltaSync = config.enableDeltaSync
 *       }
 *   val replicator = Replicator(replConfig)
 *   replicator.start()
 *
 * A device can simultaneously host AND replicate to other listeners.
 * Delta sync minimises LAN bandwidth when documents change incrementally.
 */
interface SyncEngine {
    suspend fun startHosting(config: SyncConfig): Result<HostingInfo>
    suspend fun stopHosting()
    suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean = false): Result<Unit>
    suspend fun disconnectFrom(deviceId: String)
    suspend fun revokeTrustedDevice(deviceId: String) {}
    fun observeStatus(): Flow<SyncStatusSnapshot>
}

data class HostingInfo(val address: String, val port: Int, val fingerprint: String)

data class SyncStatusSnapshot(
    val isHosting: Boolean,
    val hostAddress: String?,
    val activeConnections: List<ConnectedPeer>,
    val lastSyncAt: Long?,
    val pendingConflicts: Int,
    val lastError: String?,
    val pairingToken: String? = null,
    val pairedDeviceCount: Int = 0
)

data class ConnectedPeer(val deviceId: String, val displayName: String, val direction: SyncDirection)
enum class SyncDirection { PUSH_PULL, PUSH_ONLY, PULL_ONLY }
