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
internal class SyncDiscovery(
    private val repo: IJournalRepository,
    private val tlsIdentity: TlsIdentityManager,
    private val _status: MutableStateFlow<SyncStatusSnapshot>,
    private val appendDebug: (String) -> Unit
) {

    // ---- WebSocket continuous sync ----

    /**
     * One-shot HostInfo probe with its client always closed (audit: leaked
     * CIO engines on every failed connect). Returns null when unreachable so
     * callers treat the capability as unknown rather than absent.
     */
    suspend fun probeHostInfo(peer: DiscoveredPeer): HostInfo? {
        val probe = KtorSyncClient(repo = repo, tlsIdentity = tlsIdentity)
        return try {
            val info = probe.requestHostInfo(peer.host, peer.port).getOrNull()
            if (info != null) warnOnProtocolMismatch(info, "capability probe")
            info
        } catch (_: Exception) {
            null
        } finally {
            try { probe.close() } catch (_: Exception) {}
        }
    }

    /**
     * Contract task 9: a protocol version mismatch WARNs through the debug
     * log and surfaces as a lastError-style status line. It never rejects
     * the connection: warn vs hard-reject was not pinned by the contract and
     * SYNC-JVM chose warn so an older peer keeps working while the user is
     * told to upgrade both ends.
     */
    fun warnOnProtocolMismatch(info: HostInfo, context: String) {
        if (info.protocolVersion != SYNC_PROTOCOL_VERSION) {
            appendDebug(
                "WARNING: $context host protocol v${info.protocolVersion} != local " +
                    "v$SYNC_PROTOCOL_VERSION; continuing (update recommended)"
            )
            _status.value = _status.value.copy(
                lastError = "Protocol version mismatch: host v${info.protocolVersion}, local v$SYNC_PROTOCOL_VERSION"
            )
        }
    }
}
