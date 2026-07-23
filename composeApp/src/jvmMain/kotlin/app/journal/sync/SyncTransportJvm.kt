package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.model.SyncConfig
import app.journal.sync.DiscoveryMode
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Background coroutine scope for mDNS and other long-lived tasks
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Mutual exclusion for sync operations — prevents concurrent pairing/trust-store races.
    private val syncLock = Mutex()

    // Active discovered peers from LAN scanning
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    // Debug log — keeps the last 200 sync-related events for the UI debug viewer
    private val _debugLog = MutableSharedFlow<String>(replay = 200)
    private fun appendDebug(msg: String) {
        Log.withTag("SyncTransport").i { msg }
        _debugLog.tryEmit("[${timestamp()}] $msg")
    }
    private fun timestamp(): String =
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

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
                appendDebug("Starting sync server on port ${config.listenerPort}")
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
                appendDebug("Server started on ${info.address}:${info.port} (fp=$fp)")
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
                // Ensure mDNS service is registered for LAN discovery
                if (lanDiscovery == null) {
                    try {
                        val discovery = LanDiscovery()
                        lanDiscovery = discovery
                        backgroundScope.launch {
                            discovery.startDiscovery().collect { event ->
                                when (event) {
                                    is LanDiscoveryEvent.PeerFound -> {
                                        appendDebug("mDNS found: ${event.peer.displayName} (${event.peer.host}:${event.peer.port})")
                                        _discoveredPeers.value = _discoveredPeers.value
                                            .filter { it.deviceId != event.peer.deviceId }
                                            .plus(event.peer)
                                    }
                                    is LanDiscoveryEvent.PeerLost -> {
                                        _discoveredPeers.value = _discoveredPeers.value
                                            .filter { it.deviceId != event.deviceId }
                                    }
                                    is LanDiscoveryEvent.DiscoveryError -> {
                                        Log.withTag("SyncTransport").w { "Discovery: ${event.reason}" }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.withTag("SyncTransport").w { "mDNS init failed: ${e.message}" }
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
        appendDebug("Sync server stopped")
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        lanDiscovery?.unregisterService()
        lanDiscovery?.stop()
        lanDiscovery = null
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
            syncLock.withLock {
                try {
                    val fp = deviceFingerprint // ensure identity

                    // Try to find an existing trusted peer by fingerprint or deviceId
                    val existingPeer = peer.fingerprint?.let { trustStore.getPeer(it) }
                        ?: (peer.deviceId?.let { trustStore.getPeerById(it) })

                    if (existingPeer != null) {
                        appendDebug("Found trusted peer: ${existingPeer.displayName} (fp=${existingPeer.fingerprint.take(8)}...)")
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
                            appendDebug("Pushing changes to ${peer.host}:${peer.port} since $since")
                            val exchange = client.pushChanges(
                                host = peer.host, port = peer.port,
                                deviceId = deviceId, deviceName = deviceDisplayName, since = since
                            )
                            if (exchange.isFailure) {
                                val error = exchange.exceptionOrNull()
                                appendDebug("Push failed: ${error?.message}")
                                _status.value = _status.value.copy(lastError = error?.message)
                                return@withLock Result.failure(error ?: Exception("Sync exchange failed"))
                            }

                            lastSyncTime = System.currentTimeMillis()
                            trustStore.updateLastSeen(existingPeer.deviceId)
                            val cp = ConnectedPeer(existingPeer.deviceId, existingPeer.displayName, SyncDirection.PUSH_PULL)
                            if (activePeers.none { it.deviceId == cp.deviceId }) activePeers.add(cp)
                            updateStatus()
                            appendDebug("Sync with ${existingPeer.displayName} succeeded")

                            if (continuous) {
                                try {
                                    startContinuousSync(peer)
                                } catch (_: Exception) { }
                            }
                            Result.success(Unit)
                        } finally {
                            client.close()
                        }
                    } else {
                        appendDebug("No existing peer by fingerprint/deviceId for ${peer.host}:${peer.port}")

                        // If no pairing token was provided, try to identify the host by fingerprint
                        if (peer.pairingToken.isNullOrBlank()) {
                            appendDebug("No pairing token — attempting fingerprint-based re-connect")
                            try {
                                val probeClient = KtorSyncClient(
                                    repo = repo,
                                    tlsIdentity = tlsIdentity,
                                    trustedFingerprint = null
                                )
                                try {
                                    val hostInfo = probeClient.requestHostInfo(peer.host, peer.port)
                                    if (hostInfo.isSuccess) {
                                        val info = hostInfo.getOrThrow()
                                        appendDebug("Host at ${peer.host}:${peer.port} has fp=${info.fingerprint.take(8)}...")
                                        val knownPeer = trustStore.getPeer(info.fingerprint)
                                        if (knownPeer != null) {
                                            appendDebug("Already paired with ${knownPeer.displayName} — re-using stored secret")
                                            val trustedPeer = DiscoveredPeer(
                                                deviceId = knownPeer.deviceId,
                                                displayName = knownPeer.displayName,
                                                host = peer.host,
                                                port = peer.port,
                                                isTrusted = true,
                                                fingerprint = knownPeer.fingerprint
                                            )
                                            return@withLock syncWith(trustedPeer, continuous)
                                        }
                                        appendDebug("Host fingerprint not in trust store — needs pairing")
                                    } else {
                                        appendDebug("Could not reach ${peer.host}:${peer.port} for fingerprint probe")
                                    }
                                } finally {
                                    probeClient.close()
                                }
                            } catch (e: Exception) {
                                appendDebug("Fingerprint probe failed: ${e.message}")
                            }
                        }

                        // Standard pairing flow
                        val pairResult = pairWithPeer(peer)
                        if (pairResult.isFailure) {
                            val error = pairResult.exceptionOrNull()
                            appendDebug("Pairing failed: ${error?.message}")
                            _status.value = _status.value.copy(lastError = error?.message)
                            return@withLock Result.failure(error ?: Exception("Pairing failed"))
                        }
                        appendDebug("Pairing succeeded")
                        val nowTrusted = trustStore.getPeerById(pairResult.getOrThrow().hostDeviceId)
                        if (nowTrusted != null) {
                            val trustedPeer = DiscoveredPeer(
                                deviceId = nowTrusted.deviceId,
                                displayName = nowTrusted.displayName,
                                host = peer.host,
                                port = peer.port,
                                isTrusted = true,
                                fingerprint = nowTrusted.fingerprint
                            )
                            appendDebug("Re-syncing with trusted peer ${nowTrusted.displayName}")
                            return@withLock syncWith(trustedPeer, continuous)
                        }
                        appendDebug("CRITICAL: Peer stored during pairing but not found by hostDeviceId")
                        Result.failure(Exception("Pairing completed but device not found in trust store"))
                    }
                } catch (e: Exception) {
                    appendDebug("syncWith exception: ${e.message}")
                    _status.value = _status.value.copy(lastError = e.message)
                    Result.failure(e)
                }
            }
        }

    private suspend fun pairWithPeer(peer: DiscoveredPeer): Result<DevicePairingResult> =
        withContext(Dispatchers.IO) {
            try {
                val token = peer.pairingToken
                if (token.isNullOrBlank()) {
                    appendDebug("pairWithPeer: no token provided for ${peer.host}:${peer.port}")
                    return@withContext Result.failure(
                        Exception("Pairing token required. Enter the token shown on the host device.")
                    )
                }

                appendDebug("pairWithPeer: checking host info at ${peer.host}:${peer.port}")
                val client = KtorSyncClient(
                    repo = repo,
                    tlsIdentity = tlsIdentity,
                    trustedFingerprint = null
                )
                try {
                    val hostInfo = client.requestHostInfo(peer.host, peer.port)
                    if (hostInfo.isFailure) {
                        appendDebug("pairWithPeer: host unreachable: ${hostInfo.exceptionOrNull()?.message}")
                        return@withContext Result.failure(
                            hostInfo.exceptionOrNull() ?: Exception("Could not reach peer for pairing")
                        )
                    }
                    appendDebug("pairWithPeer: host reachable, completing pairing with token=$token")
                    val result = client.completePairing(
                        host = peer.host, port = peer.port,
                        token = token,
                        clientDeviceId = deviceId,
                        clientDeviceName = deviceDisplayName,
                        clientFingerprint = deviceFingerprint
                    )

                    if (result.isSuccess) {
                        val pr = result.getOrThrow()
                        appendDebug("pairWithPeer: success, hostDeviceId=${pr.hostDeviceId}, sharedSecret=${pr.sharedSecret.take(8)}...")
                        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                            deviceId = pr.hostDeviceId,
                            displayName = pr.hostDeviceName,
                            fingerprint = pr.hostFingerprint,
                            sharedSecret = pr.sharedSecret,
                            pairedAt = System.currentTimeMillis()
                        ))
                        appendDebug("pairWithPeer: stored trusted peer ${pr.hostDeviceName} (id=${pr.hostDeviceId})")
                        updateStatus()
                    } else {
                        appendDebug("pairWithPeer: host rejected pairing: ${result.exceptionOrNull()?.message}")
                    }
                    result
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                appendDebug("pairWithPeer exception: ${e.message}")
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
        val rawFlow = discovery.startDiscovery()
        val srv = server
        if (srv != null && srv.isRunning) {
            discovery.registerService(srv.actualPort, deviceId, deviceFingerprint)
        }
        // Route events into the shared discovered-peers state for screens to observe
        return rawFlow.onEach { event ->
            when (event) {
                is LanDiscoveryEvent.PeerFound -> {
                    _discoveredPeers.value = _discoveredPeers.value
                        .filter { it.deviceId != event.peer.deviceId }
                        .plus(event.peer)
                }
                is LanDiscoveryEvent.PeerLost -> {
                    _discoveredPeers.value = _discoveredPeers.value
                        .filter { it.deviceId != event.deviceId }
                }
                is LanDiscoveryEvent.DiscoveryError -> { }
            }
        }
    }

    override suspend fun stopDiscovery() {
        val srv = server
        if (srv == null || !srv.isRunning) {
            lanDiscovery?.stop()
            lanDiscovery = null
        }
        // If server is still running, keep LanDiscovery alive for mDNS service registration
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

    override fun observeDebugLog(): Flow<String> = _debugLog

    override fun observeDiscoveredPeers(): Flow<List<DiscoveredPeer>> =
        _discoveredPeers.asStateFlow()

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
