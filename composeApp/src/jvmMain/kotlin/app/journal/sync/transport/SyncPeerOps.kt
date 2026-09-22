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
internal class SyncPeerOps(
    private val repo: IJournalRepository,
    private val tlsIdentity: TlsIdentityManager,
    private val trustStore: DeviceTrustStore,
    private val deviceId: String,
    private val deviceDisplayName: String,
    private val deviceFingerprint: String,
    private val appendDebug: (String) -> Unit,
    private val updateStatus: () -> Unit,
    private val warnOnProtocolMismatch: (HostInfo, String) -> Unit
) {

    suspend fun pairWithPeer(peer: DiscoveredPeer): Result<DevicePairingResult> =
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
                    // Never log the pairing token: the debug log replays to the
                    // UI viewer, and the token is the secret that wraps the key.
                    appendDebug("pairWithPeer: host reachable, completing pairing")
                    val info = hostInfo.getOrThrow()
                    warnOnProtocolMismatch(info, "pairing")
                    val result = client.completePairing(
                        host = peer.host, port = peer.port,
                        token = token,
                        clientDeviceId = deviceId,
                        clientDeviceName = deviceDisplayName,
                        clientFingerprint = deviceFingerprint,
                        // Contract g: reuse the HostInfo key already fetched
                        // here; no extra round trip and no key material logged.
                        hostEcdhPublicKeyB64 = info.ecdhPublicKeyB64
                    )

                    if (result.isSuccess) {
                        val pr = result.getOrThrow()
                        // Never log secret material, not even truncated: the debug
                        // log replays 200 lines to the UI viewer.
                        appendDebug("pairWithPeer: success, hostDeviceId=${pr.hostDeviceId}")
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
}
