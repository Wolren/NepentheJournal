package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.model.SyncConfig
import app.journal.sync.DiscoveryMode
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation of SyncEngine using TLS + HMAC-authenticated HTTP transport
 * with WebSocket continuous sync and LAN discovery.
 *
 * On first run, generates a self-signed TLS identity.
 * Sync with unknown peers triggers the pairing protocol (token exchange).
 * Subsequent syncs use HMAC-SHA256 signed requests over HTTPS with cert pinning.
 * Continuous sync uses WebSocket for low-latency mutation push.
 */
class SyncTransport(
    private val repo: JournalRepository,
    private val dataDir: String = platformSyncDataDir()
) : SyncEngine {

    // Persistent infrastructure
    private val tlsIdentity = TlsIdentityManager(dataDir)
    private val trustStore = DeviceTrustStore(dataDir)
    private val authenticator = SyncAuthenticator(trustStore)

    // Derived identity
    private val deviceFingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${deviceFingerprint.take(16)}" }
    val deviceDisplayName: String by lazy { platformDeviceName() }

    private var server: KtorSyncServer? = null
    private var lastSyncTime: Long? = null
    private var activePeers = Collections.synchronizedList(mutableListOf<ConnectedPeer>())
    private var tokenRefreshJob: Job? = null

    private val _status = MutableStateFlow(SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null,
        pairingToken = null, pairedDeviceCount = 0
    ))

    /** Current pairing token, if hosting and waiting for pairing. */
    val pairingToken: String? get() = _status.value.pairingToken

    // ---- WebSocket continuous sync ----
    private data class WsConnection(
        val session: WebSocketSession,
        val client: KtorSyncClient,
        val mutationJob: Job
    )
    private val wsConnections = ConcurrentHashMap<String, WsConnection>()

    // ---- LAN discovery ----
    private var lanDiscovery: LanDiscovery? = null

    /** Revoke a previously paired device. */
    fun revokeDevice(deviceId: String) {
        trustStore.revokeDevice(deviceId)
        activePeers.removeAll { it.deviceId == deviceId }
        updateStatus()
    }

    override suspend fun startHosting(config: SyncConfig): Result<HostingInfo> =
        withContext(Dispatchers.IO) {
            try {
                server?.stop()
                val fp = deviceFingerprint
                val srv = KtorSyncServer(
                    repo = repo,
                    port = config.listenerPort,
                    tlsIdentity = tlsIdentity,
                    trustStore = trustStore,
                    authenticator = authenticator,
                    onConnection = { msg ->
                        updateStatus()
                        _status.value = _status.value.copy(lastError = msg)
                    }
                )

                val info = srv.start() // throws on failure
                server = srv
                authenticator.generatePairingToken()
                val token = authenticator.currentPairingToken()
                _status.value = _status.value.copy(
                    isHosting = true,
                    hostAddress = "${info.address}:${info.port}",
                    pairingToken = token,
                    pairedDeviceCount = trustStore.count()
                )
                tokenRefreshJob?.cancel()
                tokenRefreshJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                    while (isActive) {
                        delay(60_000L)
                        authenticator.generatePairingToken()
                        _status.value = _status.value.copy(
                            pairingToken = authenticator.currentPairingToken()
                        )
                    }
                }
                lanDiscovery?.registerService(info.port, deviceId, deviceFingerprint)
                Result.success(info)
            } catch (e: Exception) {
                Log.withTag("SyncTransport").e(e) { "startHosting: failed" }
                _status.value = _status.value.copy(lastError = e.message)
                Result.failure(e)
            }
        }

    override suspend fun stopHosting() {
        server?.stop()
        server = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        lanDiscovery?.unregisterService()
        authenticator.clearPendingPairing()
        activePeers.clear()
        _status.value = _status.value.copy(
            isHosting = false, hostAddress = null,
            activeConnections = emptyList(), pairingToken = null,
            lastError = null
        )
    }

    override suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val fp = deviceFingerprint // ensure identity

                val existingPeer = peer.fingerprint?.let { trustStore.getPeer(it) }
                    ?: (peer.deviceId?.let { trustStore.getPeerById(it) })

                if (existingPeer != null) {
                    val secret = existingPeer.sharedSecret
                    val client = KtorSyncClient(
                        repo = repo,
                        tlsIdentity = tlsIdentity,
                        deviceId = deviceId,
                        deviceFingerprint = deviceFingerprint,
                        deviceName = deviceDisplayName,
                        sharedSecret = secret,
                        trustedFingerprint = existingPeer.fingerprint
                    )
                    try {
                        val since = lastSyncTime ?: 0L
                        val exchange = client.pushChanges(
                            host = peer.host, port = peer.port,
                            deviceId = deviceId, deviceName = deviceDisplayName, since = since
                        )
                        if (exchange.isFailure) {
                            val error = exchange.exceptionOrNull()
                            _status.value = _status.value.copy(lastError = error?.message)
                            return@withContext Result.failure(error ?: Exception("Sync exchange failed"))
                        }

                        lastSyncTime = System.currentTimeMillis()
                        trustStore.updateLastSeen(existingPeer.deviceId)
                        val cp = ConnectedPeer(existingPeer.deviceId, existingPeer.displayName, SyncDirection.PUSH_PULL)
                        if (activePeers.none { it.deviceId == cp.deviceId }) activePeers.add(cp)
                        updateStatus()

                        // If continuous requested, upgrade to WebSocket
                        if (continuous) {
                            try {
                                startContinuousSync(peer)
                            } catch (_: Exception) {
                                // WS upgrade optional — HTTP sync still succeeded
                            }
                        }
                        Result.success(Unit)
                    } finally {
                        client.close()
                    }
                } else {
                    val pairResult = pairWithPeer(peer)
                    if (pairResult.isFailure) {
                        val error = pairResult.exceptionOrNull()
                        _status.value = _status.value.copy(lastError = error?.message)
                        return@withContext Result.failure(error ?: Exception("Pairing failed"))
                    }
                    val nowTrusted = trustStore.getPeerById(pairResult.getOrThrow().deviceId)
                    if (nowTrusted != null) {
                        val trustedPeer = DiscoveredPeer(
                            deviceId = nowTrusted.deviceId,
                            displayName = nowTrusted.displayName,
                            host = peer.host,
                            port = peer.port,
                            isTrusted = true,
                            fingerprint = nowTrusted.fingerprint
                        )
                        return@withContext syncWith(trustedPeer, continuous)
                    }
                    Result.failure(Exception("Pairing completed but device not found in trust store"))
                }
            } catch (e: Exception) {
                _status.value = _status.value.copy(lastError = e.message)
                Result.failure(e)
            }
        }

    private suspend fun pairWithPeer(peer: DiscoveredPeer): Result<DevicePairingResult> =
        withContext(Dispatchers.IO) {
            try {
                val token = peer.pairingToken
                if (token.isNullOrBlank()) {
                    return@withContext Result.failure(
                        Exception("Pairing token required. Enter the token shown on the host device.")
                    )
                }

                val client = KtorSyncClient(
                    repo = repo,
                    tlsIdentity = tlsIdentity,
                    trustedFingerprint = null
                )
                try {
                    val hostInfo = client.requestHostInfo(peer.host, peer.port)
                    if (hostInfo.isFailure) {
                        return@withContext Result.failure(
                            hostInfo.exceptionOrNull() ?: Exception("Could not reach peer for pairing")
                        )
                    }

                    val result = client.completePairing(
                        host = peer.host, port = peer.port,
                        token = token,
                        clientDeviceId = deviceId,
                        clientDeviceName = deviceDisplayName,
                        clientFingerprint = deviceFingerprint
                    )

                    if (result.isSuccess) {
                        val pr = result.getOrThrow()
                        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                            deviceId = pr.hostDeviceId,
                            displayName = pr.hostDeviceName,
                            fingerprint = pr.hostFingerprint,
                            sharedSecret = pr.sharedSecret,
                            pairedAt = System.currentTimeMillis()
                        ))
                        updateStatus()
                    }
                    result
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun disconnectFrom(deviceId: String) {
        stopContinuousSync(deviceId)
        activePeers.removeAll { it.deviceId == deviceId }
        updateStatus()
    }

    override suspend fun revokeTrustedDevice(deviceId: String) {
        revokeDevice(deviceId)
    }

    // ---- WebSocket continuous sync ----

    override suspend fun startContinuousSync(peer: DiscoveredPeer) = withContext(Dispatchers.IO) {
        try {
            val existingPeer = peer.fingerprint?.let { trustStore.getPeer(it) }
                ?: (peer.deviceId?.let { trustStore.getPeerById(it) })
                ?: return@withContext

            // Close existing connection to this peer if any
            stopContinuousSync(existingPeer.deviceId)

            val secret = existingPeer.sharedSecret
            val client = KtorSyncClient(
                repo = repo,
                tlsIdentity = tlsIdentity,
                deviceId = deviceId,
                deviceFingerprint = deviceFingerprint,
                deviceName = deviceDisplayName,
                sharedSecret = secret,
                trustedFingerprint = existingPeer.fingerprint
            )

            val session = client.connectWs(peer.host, peer.port) { delta ->
                val validationError = validateWsDelta(delta)
                if (validationError != null) {
                    Log.withTag("SyncTransport").w { "Invalid WS delta from ${peer.displayName}: $validationError" }
                    return@connectWs
                }
                repo.applyBatch(
                    sessions = delta.sessions,
                    doses = delta.doses,
                    substances = delta.substances,
                    effects = delta.effects,
                    interactions = delta.interactions,
                    notes = delta.notes,
                    timelineEvents = delta.timelineEvents,
                    customUnits = delta.customUnits
                )
                lastSyncTime = System.currentTimeMillis()
            }

            // Subscribe to local mutations and push over WebSocket
            val mutationJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                repo.mutationCount
                    .drop(1)
                    .debounce(500)
                    .collect { _ ->
                        val since = lastSyncTime ?: 0L
                        val delta = buildDelta(since)
                        if (isEmptyDelta(delta)) return@collect
                        try {
                            client.sendDelta(session, delta)
                            lastSyncTime = System.currentTimeMillis()
                        } catch (_: Exception) {
                            // WS disconnected — mutation job will be recreated on next reconnect
                        }
                    }
            }

            wsConnections[existingPeer.deviceId] = WsConnection(session, client, mutationJob)

            val cp = ConnectedPeer(existingPeer.deviceId, existingPeer.displayName, SyncDirection.PUSH_PULL)
            if (activePeers.none { it.deviceId == cp.deviceId }) activePeers.add(cp)
            updateStatus()
        } catch (e: Exception) {
            _status.value = _status.value.copy(lastError = "WS connect: ${e.message}")
        }
    }

    override suspend fun stopContinuousSync(deviceId: String) {
        wsConnections.remove(deviceId)?.let { conn ->
            conn.mutationJob.cancel()
            conn.session.close()
            conn.client.close()
        }
        activePeers.removeAll { it.deviceId == deviceId }
        updateStatus()
    }

    // ---- LAN discovery ----

    override fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent> {
        lanDiscovery?.stop()
        val discovery = LanDiscovery()
        lanDiscovery = discovery
        val flow = discovery.startDiscovery()
        val srv = server
        if (srv != null && srv.isRunning) {
            discovery.registerService(srv.actualPort, deviceId, deviceFingerprint)
        }
        return flow
    }

    override suspend fun stopDiscovery() {
        lanDiscovery?.stop()
        lanDiscovery = null
    }

    override suspend fun connectManually(host: String, port: Int, token: String?): Result<Unit> {
        val peer = DiscoveredPeer(
            deviceId = null,
            displayName = host,
            host = host,
            port = port,
            isTrusted = false,
            fingerprint = null,
            pairingToken = token
        )
        return syncWith(peer, continuous = false)
    }

    // ---- Status ----

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status.asStateFlow()

    override fun trustedDevices(): List<TrustedDeviceInfo> =
        trustStore.listPeers().map { peer ->
            TrustedDeviceInfo(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                fingerprint = peer.fingerprint,
                pairedAt = peer.pairedAt,
                lastSeenAt = peer.lastSeenAt
            )
        }

    private fun updateStatus() {
        _status.value = _status.value.copy(
            activeConnections = activePeers.toList(),
            lastSyncAt = lastSyncTime,
            pendingConflicts = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
            pairingToken = authenticator.currentPairingToken(),
            pairedDeviceCount = trustStore.count(),
            continuousPeers = wsConnections.size
        )
    }

    // ---- Helpers ----

    private fun buildDelta(since: Long) = WsDelta(
        seq = System.nanoTime(),
        sessions = repo.sessions.value.filter { it.updatedAt > since },
        doses = repo.doses.value.filter { it.updatedAt > since },
        substances = repo.substances.value.filter { it.updatedAt > since },
        effects = repo.effects.value.filter { it.updatedAt > since },
        interactions = repo.interactions.value.filter { it.updatedAt > since },
        notes = repo.notes.value.filter { it.updatedAt > since },
        timelineEvents = repo.timelineEvents.value.filter { it.updatedAt > since },
        customUnits = repo.customUnits.value.filter { it.updatedAt > since }
    )

    private fun isEmptyDelta(d: WsDelta): Boolean =
        d.sessions.isEmpty() && d.doses.isEmpty() && d.substances.isEmpty() &&
        d.effects.isEmpty() && d.interactions.isEmpty() && d.notes.isEmpty() &&
        d.timelineEvents.isEmpty() && d.customUnits.isEmpty()
}
