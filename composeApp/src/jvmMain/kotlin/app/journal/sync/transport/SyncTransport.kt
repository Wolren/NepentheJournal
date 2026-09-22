package app.journal.sync

import app.journal.data.IJournalRepository
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

    // Persistent infrastructure
    private val tlsIdentity = TlsIdentityManager(dataDir)
    // Static P-256 pairing key (contract g), persisted next to identity.p12.
    private val ecdhIdentity = EcdhIdentityManager(dataDir)
    private val trustStore = DeviceTrustStore(dataDir)
    private val authenticator = SyncAuthenticator(trustStore)

    // Derived identity
    private val deviceFingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${deviceFingerprint.take(16)}" }
    val deviceDisplayName: String by lazy { platformDeviceName() }

    private var server: KtorSyncServer? = null
    internal var lastSyncTime: Long? = null
    private var activePeers = Collections.synchronizedList(mutableListOf<ConnectedPeer>())
    private var tokenRefreshJob: Job? = null

    // Background coroutine scope for mDNS and other long-lived tasks
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Mutual exclusion for sync operations; prevents concurrent pairing/trust-store races.
    private val syncLock = Mutex()

    // Active discovered peers from LAN scanning
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    // Debug log: keeps the last 200 sync-related events for the UI debug viewer
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
    private val wsConnections = ConcurrentHashMap<String, WsConnection>()

    // ---- LAN discovery ----
    private var lanDiscovery: LanDiscovery? = null

    // wave2 file split (file-size-governor): cohesive private-method groups
    // moved verbatim into helper classes this transport owns; state is
    // passed by reference (same instances), including bound private helpers,
    // so cursors, throttles, and connections behave identically.
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

    /** Revoke a previously paired device. */
    fun revokeDevice(deviceId: String) {
        trustStore.revokeDevice(deviceId)
        // PBKDF2 key cache invalidation (audit perf note): cached AES keys are
        // only ever looked up by their own secret, so a revoked secret can
        // never be queried again; purging here also drops any OTHER peer's
        // cached derivation so a revoke cannot leave key material resident.
        clearAesKeyCache()
        activePeers.removeAll { it.deviceId == deviceId }
        // Drop the client-side WS connection, if any, using the same
        // teardown as stopContinuousSync (suspending close runs off-thread:
        // revoke is called from UI event handlers, never suspend).
        wsConnections.remove(deviceId)?.let { conn ->
            conn.mutationJob.cancel()
            conn.heartbeatJob?.cancel()
            conn.incomingJob?.cancel()
            backgroundScope.launch {
                try { conn.session.close() } catch (_: Exception) {}
                try { conn.client.close() } catch (_: Exception) {}
            }
        }
        // Drop live server-side WS sessions for the revoked device, so an
        // open /sync/ws cannot keep pushing after revocation.
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
                    pairedDeviceCount = trustStore.count()
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
                            trustedFingerprint = existingPeer.fingerprint,
                            persistAfterApply = persistAfterApply
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

                            // Contract d pin_deviation_flag: the ONLY wall-clock
                            // value allowed to become the cursor is
                            // ChunkedPushResult.cycleStart, captured BEFORE the
                            // batch was built, and it is applied exclusively
                            // through advanceCursorIfAllSucceeded over the real
                            // per-slice acks. A partial push keeps the old cursor
                            // so the unconfirmed remainder is resent next cycle.
                            val push = exchange.getOrThrow()
                            lastSyncTime = advanceCursorIfAllSucceeded(since, push.cycleStart, push.acks)
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
                            appendDebug("No pairing token: attempting fingerprint-based re-connect")
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
                                            // Challenge the host before re-using the stored secret:
                                            // mDNS is unauthenticated and fingerprints are public,
                                            // so a spoofed host must prove it knows the secret.
                                            val hostProven = probeClient.verifyHostIdentity(
                                                peer.host, peer.port, deviceId, knownPeer.sharedSecret
                                            )
                                            if (hostProven) {
                                                appendDebug("Host identity verified (challenge-response): re-using stored secret for ${knownPeer.displayName}")
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
                            } catch (e: Exception) {
                                appendDebug("Fingerprint probe failed: ${e.message}")
                            }
                        }

                        // Standard pairing flow
                        val pairResult = peerOps.pairWithPeer(peer)
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

            // Close existing connection to this peer if any
            stopContinuousSync(existingPeer.deviceId)

            // Contract section f: ask the host BEFORE opening a socket. iOS
            // hosts advertise wsSupported=false; reconnecting blindly would
            // burn the whole retry budget against a port that never answers.
            // An unreachable host leaves the capability unknown; proceed and
            // let the connect error surface as before.
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

            // Per-connection seq counter and in-flight push ledger: both live
            // on the WsConnection so every reconnect starts a fresh epoch
            // (contract section a: first delta is seq 1).
            val seqCounter = AtomicLong(0L)
            val pendingPushes = PendingWsPushes()

            // Subscribe to local mutations and push over WebSocket
            val mutationJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                repo.mutationCount
                    .drop(1)
                    .debounce(500)
                    .collect { _ ->
                        val since = lastSyncTime ?: 0L
                        val seq = seqCounter.incrementAndGet()
                        // Contract d: cycleStart is captured BEFORE the delta is
                        // built and is the only wall-clock value that may ever
                        // move lastSyncTime; it does so only via the server ack.
                        val cycleStart = System.currentTimeMillis()
                        val delta = continuousOps.buildDelta(since, seq)
                        if (continuousOps.isEmptyDelta(delta)) return@collect
                        try {
                            pendingPushes.push(seq, cycleStart)
                            client.sendDelta(session, delta)
                        } catch (_: Exception) {
                            // Never sent: drop the ledger entry so it cannot
                            // block later ack advances; the data is resent by
                            // the next mutation cycle or the HTTP push.
                            pendingPushes.cancel(seq)
                            // WS disconnected; mutation job will be recreated on next reconnect
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

            // Launch incoming frame reader (handles WsPong, WsDelta from server)
            wsConnection.incomingJob = continuousOps.launchIncomingReader(existingPeer.deviceId, session, wsConnection)
            // Launch heartbeat (sends WsPing every 30s, triggers reconnect if no pong in 10s)
            wsConnection.heartbeatJob = continuousOps.launchHeartbeat(existingPeer.deviceId, session, wsConnection)

            wsConnections[existingPeer.deviceId] = wsConnection
            registered = true
            } finally {
                // Audit leak fix: a failed handshake or setup must close the
                // CIO engine. Once registered, the connection owns its own
                // teardown (stopContinuousSync / revoke / stale cleanup).
                if (!registered) {
                    try { client.close() } catch (_: Exception) {}
                }
            }

            // Ensure stale cleanup is running
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
                    // isTrusted must reflect the trust store, not mDNS metadata:
                    // every host publishes a fingerprint, so the presence of one
                    // says nothing about pairing state.
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
        val now = System.currentTimeMillis()
        _status.value = _status.value.copy(
            activeConnections = activePeers.toList(),
            lastSyncAt = lastSyncTime,
            pendingConflicts = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
            pairingToken = authenticator.currentPairingToken(),
            tokenExpiresAt = if (authenticator.currentPairingToken() != null) now + 120_000L else null,
            pairedDeviceCount = trustStore.count(),
            continuousPeers = wsConnections.size
        )
    }
}
