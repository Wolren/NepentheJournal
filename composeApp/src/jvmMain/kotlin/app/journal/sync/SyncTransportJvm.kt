package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * JVM implementation of SyncEngine using TLS + HMAC-authenticated HTTP transport.
 *
 * On first run, generates a self-signed TLS identity.
 * Sync with unknown peers triggers the pairing protocol (token exchange).
 * All subsequent syncs use HMAC-SHA256 signed requests over HTTPS with cert pinning.
 */
class SyncTransport(private val repo: JournalRepository) : SyncEngine {

    // Persistent infrastructure
    private val tlsIdentity = TlsIdentityManager()
    private val trustStore = DeviceTrustStore()
    private val authenticator = SyncAuthenticator(trustStore)

    // Derived identity
    private val deviceFingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${deviceFingerprint.take(16)}" }
    val deviceDisplayName: String = "Windows Desktop"

    private var server: KtorSyncServer? = null
    private var lastSyncTime: Long? = null
    private var activePeers = mutableListOf<ConnectedPeer>()

    private val _status = MutableStateFlow(SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null,
        pairingToken = null, pairedDeviceCount = 0
    ))

    /** Current pairing token, if hosting and waiting for pairing. */
    val pairingToken: String? get() = _status.value.pairingToken

    /** List of trusted paired devices. */
    fun trustedDevices(): List<DeviceTrustStore.TrustedPeer> = trustStore.listPeers()

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
                val fp = deviceFingerprint // force identity generation

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

                val result = srv.start()
                result.onSuccess { info ->
                    server = srv
                    val token = authenticator.currentPairingToken()
                    _status.value = _status.value.copy(
                        isHosting = true,
                        hostAddress = "${info.address}:${info.port}",
                        pairingToken = token,
                        pairedDeviceCount = trustStore.count()
                    )
                }
                result
            } catch (e: Exception) {
                _status.value = _status.value.copy(lastError = e.message)
                Result.failure(e)
            }
        }

    override suspend fun stopHosting() {
        server?.stop()
        server = null
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

                // Check if this peer is already trusted
                val existingPeer = peer.fingerprint?.let { trustStore.getPeer(it) }
                    ?: (peer.deviceId?.let { trustStore.getPeerById(it) })

                if (existingPeer != null) {
                    // Trusted: do HMAC-signed sync
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

                    val since = lastSyncTime ?: 0L
                    // Atomic exchange: push sends local changes AND returns server changes
                    val exchange = client.pushChanges(
                        host = peer.host, port = peer.port,
                        deviceId = deviceId, deviceName = deviceDisplayName, since = since
                    )
                    if (exchange.isFailure) {
                        client.close()
                        val error = exchange.exceptionOrNull()
                        _status.value = _status.value.copy(lastError = error?.message)
                        return@withContext Result.failure(error ?: Exception("Sync exchange failed"))
                    }

                    lastSyncTime = System.currentTimeMillis()
                    trustStore.updateLastSeen(existingPeer.deviceId)
                    val cp = ConnectedPeer(existingPeer.deviceId, existingPeer.displayName, SyncDirection.PUSH_PULL)
                    if (activePeers.none { it.deviceId == cp.deviceId }) activePeers.add(cp)
                    updateStatus()
                    client.close()
                    Result.success(Unit)

                } else {
                    // Not trusted: initiate pairing protocol
                    val pairResult = pairWithPeer(peer)
                    if (pairResult.isFailure) {
                        val error = pairResult.exceptionOrNull()
                        _status.value = _status.value.copy(lastError = error?.message)
                        return@withContext Result.failure(error ?: Exception("Pairing failed"))
                    }
                    // After pairing, retry sync
                    val nowTrusted = trustStore.getPeerById(pairResult.getOrThrow().deviceId)
                    if (nowTrusted != null) {
                        // Recursive call with now-trusted peer
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

    /**
     * Pair with an untrusted peer using a user-entered pairing token.
     * The token was visually read from the host's screen and entered by the user.
     */
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
                    trustedFingerprint = null // TOFU mode for pairing
                )

                // Step 1: Get host device info (does NOT return the token)
                val hostInfo = client.requestHostInfo(peer.host, peer.port)
                if (hostInfo.isFailure) {
                    client.close()
                    return@withContext Result.failure(
                        hostInfo.exceptionOrNull() ?: Exception("Could not reach peer for pairing")
                    )
                }

                val info = hostInfo.getOrThrow()

                // Step 2: Complete pairing with the USER-ENTERED token
                val result = client.completePairing(
                    host = peer.host,
                    port = peer.port,
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

                client.close()
                result
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun disconnectFrom(deviceId: String) {
        activePeers.removeAll { it.deviceId == deviceId }
        updateStatus()
    }

    override suspend fun revokeTrustedDevice(deviceId: String) {
        revokeDevice(deviceId)
        // Base implementation already disconnects + removes from trust store
    }

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status.asStateFlow()

    private fun updateStatus() {
        _status.value = _status.value.copy(
            activeConnections = activePeers.toList(),
            lastSyncAt = lastSyncTime,
            pendingConflicts = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
            pairingToken = authenticator.currentPairingToken(),
            pairedDeviceCount = trustStore.count()
        )
    }
}
