package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.data.SEED_BUNDLED_FINGERPRINT
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.TimeoutCancellationException

/**
 * JVM implementation of SyncEngine using HMAC-authenticated plain HTTP
 * with AES-256-GCM body encryption and WebSocket continuous sync.
 *
 * On first run, generates a self-signed identity (cert used for the
 * fingerprint/device id only, NOT for TLS).
 * Sync with unknown peers triggers the pairing protocol (token exchange).
 * Subsequent syncs use HMAC-SHA256 signed requests over plain HTTP with
 * AES-256-GCM encrypted bodies (LAN threat model; see SYNC-PAIRING-AUDIT).
 * Continuous sync uses WebSocket for low-latency mutation push.
 */
class SyncTransport(
    private val repo: IJournalRepository,
    private val dataDir: String = platformSyncDataDir(),
    private val persistAfterApply: (() -> Unit)? = null
) : SyncEngine {

    private val tlsIdentity = TlsIdentityManager(dataDir)
    // Static P-256 pairing key (contract g), persisted next to identity.p12.
    private val ecdhIdentity = EcdhIdentityManager(dataDir)
    private val trustStore = DeviceTrustStore(dataDir)
    private val authenticator = SyncAuthenticator(trustStore)

    private val deviceFingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${deviceFingerprint.take(16)}" }
    val deviceDisplayName: String by lazy { platformDeviceName() }

    private var server: KtorSyncServer? = null
    private val cursorStore = SyncCursorStore(dataDir)

    /** Per-peer bundled-library fingerprint from /info, so a repeat sync does not ask again. */
    private val peerSeedFingerprints = ConcurrentHashMap<String, String>()

    /** This device's own cursor; persists across launches so a restart resumes instead of re-pushing everything. */
    internal var lastSyncTime: Long?
        get() = cursorStore.read()
        set(value) {
            if (value != null) cursorStore.write(value)
        }

    private var activePeers = Collections.synchronizedList(mutableListOf<ConnectedPeer>())
    private var tokenRefreshJob: Job? = null

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val syncLock = Mutex()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    // Debug log: keeps the last 200 sync-related events for the ui debug viewer
    private val _debugLog = MutableSharedFlow<String>(replay = 200)
    private fun appendDebug(msg: String) {
        Log.withTag("SyncTransport").i { msg }
        _debugLog.tryEmit("[${timestamp()}] $msg")
    }
    private fun timestamp(): String =
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    private val _status = MutableStateFlow(SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = cursorStore.read(),
        pendingConflicts = 0, lastError = null,
        pairingToken = null, pairedDeviceCount = 0
    ))

    val pairingToken: String? get() = _status.value.pairingToken
    private val wsConnections = ConcurrentHashMap<String, WsConnection>()

    private var lanDiscovery: LanDiscovery? = null

    // wave2 file split (file-size-governor): cohesive private-method groups moved verbatim into helper classes this transport owns
    private val discoveryOps = SyncDiscovery(
        repo = repo, tlsIdentity = tlsIdentity, _status = _status,
        appendDebug = ::appendDebug
    )
    private val peerOps by lazy {
        SyncPeerOps(
            repo = repo, tlsIdentity = tlsIdentity, trustStore = trustStore,
            deviceId = deviceId, deviceDisplayName = deviceDisplayName,
            deviceFingerprint = deviceFingerprint,
            appendDebug = ::appendDebug, updateStatus = ::updateStatus,
            warnOnProtocolMismatch = discoveryOps::warnOnProtocolMismatch
        )
    }
    private val continuousOps = SyncContinuousSession(
        this, repo, persistAfterApply, backgroundScope, wsConnections,
        activePeers, trustStore, _status, ::appendDebug, ::updateStatus
    )

    fun revokeDevice(deviceId: String) {
        trustStore.revokeDevice(deviceId)
        // PBKDF2 key cache invalidation (audit perf note): cached AES keys are only ever looked up by their own secret
        clearAesKeyCache()
        activePeers.removeAll { it.deviceId == deviceId }
        wsConnections.remove(deviceId)?.let { conn ->
            conn.mutationJob.cancel()
            conn.heartbeatJob?.cancel()
            conn.incomingJob?.cancel()
            backgroundScope.launch {
                try { conn.session.close() } catch (_: Exception) {}
                try { conn.client.close() } catch (_: Exception) {}
            }
        }
        backgroundScope.launch {
            try { server?.closeDeviceSessions(deviceId) } catch (_: Exception) {}
        }
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
                    ecdhIdentity = ecdhIdentity,
                    trustStore = trustStore,
                    authenticator = authenticator,
                    onConnection = { msg ->
                        updateStatus()
                        _status.value = _status.value.copy(lastError = msg)
                    },
                    persistAfterApply = persistAfterApply
                )

                val info = srv.start() // throws on failure
                server = srv
                authenticator.generatePairingToken()
                val token = authenticator.currentPairingToken()
                val now = System.currentTimeMillis()
                _status.value = _status.value.copy(
                    isHosting = true,
                    hostAddress = "${info.address}:${info.port}",
                    pairingToken = token,
                    tokenExpiresAt = now + 120_000L,
                    pairedDeviceCount = trustStore.count(excluding = deviceId)
                )
                appendDebug("Server started on ${info.address}:${info.port} (fp=$fp)")
                tokenRefreshJob?.cancel()
                tokenRefreshJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                    while (isActive) {
                        delay(60_000L)
                        authenticator.generatePairingToken()
                        val now = System.currentTimeMillis()
                        _status.value = _status.value.copy(
                            pairingToken = authenticator.currentPairingToken(),
                            tokenExpiresAt = now + 120_000L
                        )
                    }
                }
                // Ensure mDNS service is registered for LAN discovery
                try {
                    if (lanDiscovery == null) {
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
                    }
                    lanDiscovery?.registerService(info.port, deviceId, deviceFingerprint)
                } catch (e: Exception) {
                    Log.withTag("SyncTransport").w { "mDNS init/register failed: ${e.message}; continuing without LAN discovery" }
                    lanDiscovery = null
                }
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
            syncLock.withLock { syncWithLocked(peer, continuous) }
        }

    /**
     * The real sync cycle: trust-store lookup, pairing, push/pull.
     *
     * [syncLock] has exactly ONE acquisition site, the public [syncWith]
     * wrapper above, and it covers the WHOLE cycle including the two
     * post-pairing re-dispatches (the fingerprint-proven reconnect and the
     * pairWithPeer success path) which recurse into THIS function instead
     * of back into [syncWith]: a kotlinx.coroutines Mutex is not reentrant,
     * so the old `return@withLock syncWith(...)` waited on the lock the
     * same coroutine already held and connectManually never returned
     * (measured 120s hang by SyncTransportLifecycleTest before the fix).
     * Because the recursion runs while the single holder still owns the
     * lock, no interleaving window exists: a second concurrent syncWith
     * parks in [syncWith] until the first cycle, including its nested
     * re-dispatch, has finished. connectManually keeps calling the public
     * [syncWith] (it holds no lock itself), so every external entry point
     * acquires exactly one lock per cycle.
     */
    private suspend fun syncWithLocked(
        peer: DiscoveredPeer,
        continuous: Boolean,
        forceFullPush: Boolean = false,
        peerSeedFingerprint: String? = null,
    ): Result<Unit> {
        return try {
            val fp = deviceFingerprint // ensure identity

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
                    trustedFingerprint = existingPeer.fingerprint,
                    persistAfterApply = persistAfterApply
                )
                try {
                    // One cursor is shared by every peer, so a newly paired device has to be sent the whole library once.
                    val since = if (forceFullPush) 0L else (lastSyncTime ?: 0L)
                    val peerSeed = peerSeedFingerprint
                        ?: peerSeedFingerprints[existingPeer.deviceId]
                        ?: discoveryOps.probeHostInfo(peer)?.seedFingerprint
                            ?.also { peerSeedFingerprints[existingPeer.deviceId] = it }
                    val skipBundled = peerSeed != null && peerSeed == SEED_BUNDLED_FINGERPRINT
                    appendDebug("Pushing changes to ${peer.host}:${peer.port} since $since (bundled rows ${if (skipBundled) "skipped" else "included"})")
                    val exchange = client.pushChanges(
                        host = peer.host, port = peer.port,
                        deviceId = deviceId, deviceName = deviceDisplayName, since = since,
                        skipBundled = skipBundled
                    )
                    if (exchange.isFailure) {
                        val error = exchange.exceptionOrNull()
                        appendDebug("Push failed: ${error?.message}")
                        _status.value = _status.value.copy(lastError = error?.message)
                        return Result.failure(error ?: Exception("Sync exchange failed"))
                    }

                    val push = exchange.getOrThrow()
                    lastSyncTime = cursorAfterCycle(since, push.cycleStart, push.acks, push.drainComplete)
                    if (!push.drainComplete) {
                        appendDebug("Pull drain incomplete — sync cursor held at $since, resuming next cycle")
                    }
                    trustStore.updateLastSeen(existingPeer.deviceId)
                    val cp = ConnectedPeer(existingPeer.deviceId, existingPeer.displayName, SyncDirection.PUSH_PULL)
                    if (activePeers.none { it.deviceId == cp.deviceId }) activePeers.add(cp)
                    updateStatus()
                    appendDebug("Sync with ${existingPeer.displayName} succeeded")

                    if (continuous) {
                        try {
                            startContinuousSync(peer)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { }
                    }
                    Result.success(Unit)
                } finally {
                    client.close()
                }
            } else {
                appendDebug("No existing peer by fingerprint/deviceId for ${peer.host}:${peer.port}")

                // Probe even when a token is typed: a manual host:port carries no fingerprint, and a left-over
                // token is usually stale by the second press, so pairing first fails what trust could resume.
                appendDebug("Attempting fingerprint-based re-connect")
                try {
                    val probeClient = KtorSyncClient(
                        repo = repo,
                        tlsIdentity = tlsIdentity,
                        trustedFingerprint = null,
                        persistAfterApply = persistAfterApply
                    )
                    try {
                        val hostInfo = probeClient.requestHostInfo(peer.host, peer.port)
                        if (hostInfo.isSuccess) {
                            val info = hostInfo.getOrThrow()
                            appendDebug("Host at ${peer.host}:${peer.port} has fp=${info.fingerprint.take(8)}...")
                            discoveryOps.warnOnProtocolMismatch(info, "first sync probe")
                            val knownPeer = trustStore.getPeer(info.fingerprint)
                            if (knownPeer != null) {
                                val hostProven = probeClient.verifyHostIdentity(
                                    peer.host, peer.port, deviceId, knownPeer.sharedSecret
                                )
                                if (hostProven) {
                                    appendDebug("Host identity verified (challenge-response): re-using stored secret for ${knownPeer.displayName}")
                                    info.seedFingerprint?.let { peerSeedFingerprints[knownPeer.deviceId] = it }
                                    val trustedPeer = DiscoveredPeer(
                                        deviceId = knownPeer.deviceId,
                                        displayName = knownPeer.displayName,
                                        host = peer.host,
                                        port = peer.port,
                                        isTrusted = true,
                                        fingerprint = knownPeer.fingerprint
                                    )
                                    return syncWithLocked(
                                        trustedPeer, continuous,
                                        peerSeedFingerprint = info.seedFingerprint
                                    )
                                }
                                appendDebug("Host failed identity challenge: NOT re-using stored secret, needs re-pairing")
                            } else {
                                appendDebug("Host fingerprint not in trust store: needs pairing")
                            }
                        } else {
                            appendDebug("Could not reach ${peer.host}:${peer.port} for fingerprint probe")
                        }
                    } finally {
                        probeClient.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendDebug("Fingerprint probe failed: ${e.message}")
                }

                val pairResult = peerOps.pairWithPeer(peer)
                if (pairResult.isFailure) {
                    val error = pairResult.exceptionOrNull()
                    appendDebug("Pairing failed: ${error?.message}")
                    _status.value = _status.value.copy(lastError = error?.message)
                    return Result.failure(error ?: Exception("Pairing failed"))
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
                    return syncWithLocked(trustedPeer, continuous, forceFullPush = true)
                }
                appendDebug("CRITICAL: Peer stored during pairing but not found by hostDeviceId")
                Result.failure(Exception("Pairing completed but device not found in trust store"))
            }
        } catch (e: CancellationException) {
            // Cancellation must propagate: it may never become a Result.failure (or a clobbered lastError) on a cancelled coroutine.
            throw e
        } catch (e: Exception) {
            appendDebug("syncWith exception: ${e.message}")
            _status.value = _status.value.copy(lastError = e.message)
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

    override suspend fun startContinuousSync(peer: DiscoveredPeer) = withContext(Dispatchers.IO) {
        try {
            val existingPeer = peer.fingerprint?.let { trustStore.getPeer(it) }
                ?: (peer.deviceId?.let { trustStore.getPeerById(it) })
                ?: return@withContext

            stopContinuousSync(existingPeer.deviceId)

            val hostInfo = discoveryOps.probeHostInfo(peer)
            if (hostInfo?.wsSupported == false) {
                appendDebug(
                    "Host ${existingPeer.displayName} does not support WebSocket sync " +
                        "(wsSupported=false); staying on HTTP push/pull"
                )
                return@withContext
            }

            val secret = existingPeer.sharedSecret
            val client = KtorSyncClient(
                repo = repo,
                tlsIdentity = tlsIdentity,
                deviceId = deviceId,
                deviceFingerprint = deviceFingerprint,
                deviceName = deviceDisplayName,
                sharedSecret = secret,
                trustedFingerprint = existingPeer.fingerprint,
                persistAfterApply = persistAfterApply
                )

            var registered = false
            try {
            val session = client.connectWs(peer.host, peer.port) { /* incoming frames handled by launchIncomingReader */ }

            val seqCounter = AtomicLong(0L)
            val pendingPushes = PendingWsPushes()

            val mutationJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                repo.mutationCount
                    .drop(1)
                    .debounce(500)
                    .collect { _ ->
                        val since = lastSyncTime ?: 0L
                        val seq = seqCounter.incrementAndGet()
                        val cycleStart = System.currentTimeMillis()
                        val delta = continuousOps.buildDelta(since, seq)
                        if (continuousOps.isEmptyDelta(delta)) return@collect
                        try {
                            pendingPushes.push(seq, cycleStart)
                            client.sendDelta(session, delta)
                        } catch (_: Exception) {
                            pendingPushes.cancel(seq)
                        }
                    }
            }

            val wsConnection = WsConnection(
                session = session,
                client = client,
                mutationJob = mutationJob,
                peerHost = peer.host,
                peerPort = peer.port,
                peerFingerprint = existingPeer.fingerprint,
                seqCounter = seqCounter,
                pendingPushes = pendingPushes
            )

            wsConnection.incomingJob = continuousOps.launchIncomingReader(existingPeer.deviceId, session, wsConnection)
            // Launch heartbeat (sends WsPing every 30s, triggers reconnect if no pong in 10s)
            wsConnection.heartbeatJob = continuousOps.launchHeartbeat(existingPeer.deviceId, session, wsConnection)

            wsConnections[existingPeer.deviceId] = wsConnection
            registered = true
            } finally {
                if (!registered) {
                    try { client.close() } catch (_: Exception) {}
                }
            }

            continuousOps.startStaleCleanup()

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
            conn.heartbeatJob?.cancel()
            conn.incomingJob?.cancel()
            conn.session.close()
            conn.client.close()
        }
        activePeers.removeAll { it.deviceId == deviceId }
        updateStatus()
    }


    override fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent> {
        lanDiscovery?.stop()
        val discovery = LanDiscovery()
        lanDiscovery = discovery
        val rawFlow = discovery.startDiscovery()
        val srv = server
        if (srv != null && srv.isRunning) {
            discovery.registerService(srv.actualPort, deviceId, deviceFingerprint)
        }
        return rawFlow.onEach { event ->
            when (event) {
                is LanDiscoveryEvent.PeerFound -> {
                    // isTrusted must reflect the trust store, not mDNS metadata: every host publishes a fingerprint
                    val trusted = event.peer.deviceId != null &&
                        trustStore.isTrustedDeviceId(event.peer.deviceId)
                    val enriched = event.peer.copy(isTrusted = trusted)
                    _discoveredPeers.value = _discoveredPeers.value
                        .filter { it.deviceId != enriched.deviceId }
                        .plus(enriched)
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


    override fun observeDebugLog(): Flow<String> = _debugLog

    override fun observeDiscoveredPeers(): Flow<List<DiscoveredPeer>> =
        _discoveredPeers.asStateFlow()

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status.asStateFlow()

    override fun trustedDevices(): List<TrustedDeviceInfo> =
        trustStore.listPeers().filter { it.deviceId != deviceId }.map { peer ->
            TrustedDeviceInfo(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                fingerprint = peer.fingerprint,
                pairedAt = peer.pairedAt,
                lastSeenAt = peer.lastSeenAt
            )
        }

    private fun updateStatus() {
        val now = System.currentTimeMillis()
        _status.value = _status.value.copy(
            activeConnections = activePeers.toList(),
            lastSyncAt = lastSyncTime,
            pendingConflicts = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
            pairingToken = authenticator.currentPairingToken(),
            tokenExpiresAt = if (authenticator.currentPairingToken() != null) now + 120_000L else null,
            pairedDeviceCount = trustStore.count(excluding = deviceId),
            continuousPeers = wsConnections.size
        )
    }
}
