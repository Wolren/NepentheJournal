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

// ---- WebSocket continuous sync ----
internal class WsConnection(
    val session: WebSocketSession,
    val client: KtorSyncClient,
    val mutationJob: Job,
    val peerHost: String,
    val peerPort: Int,
    val peerFingerprint: String,
    /** Per-connection outgoing seq counter; the first delta sent is seq 1. */
    val seqCounter: AtomicLong = AtomicLong(0L),
    /** In-flight (seq, cycleStart) pushes awaiting their WsAck. */
    val pendingPushes: PendingWsPushes = PendingWsPushes()
) {
    @Volatile
    var lastPongSeq: Long = -1L
    var heartbeatJob: Job? = null
    var incomingJob: Job? = null
}

/**
 * In-flight WS pushes for one connection in send order (contract
 * sections a and d): lastSyncTime may advance to a delta's cycleStart
 * only when its ack reports success while every earlier delta is already
 * acked. A rejected ack clears the queue WITHOUT advancing, so the
 * unsent-through data is resent by the HTTP fallback cycle. A delta that
 * failed to hit the wire is cancelled so it cannot block later advances.
 */
internal class PendingWsPushes {
    private val queue = ArrayDeque<Pair<Long, Long>>() // (seq, cycleStart)

    @Synchronized fun push(seq: Long, cycleStart: Long) { queue.addLast(seq to cycleStart) }
    @Synchronized fun cancel(seq: Long) { queue.removeAll { it.first == seq } }
    @Synchronized fun failAll() { queue.clear() }

    /** Consume the ack for [seq]; returns the cursor to advance to, or null. */
    @Synchronized fun ack(seq: Long): Long? {
        val idx = queue.indexOfFirst { it.first == seq }
        if (idx < 0) return null
        if (idx > 0) {
            // Out-of-order ack: drop it, never advance past an unacked head.
            queue.removeAt(idx)
            return null
        }
        return queue.removeFirst().second
    }
}
internal class SyncContinuousSession(
    private val owner: SyncTransport,
    private val repo: IJournalRepository,
    private val persistAfterApply: (() -> Unit)?,
    private val backgroundScope: CoroutineScope,
    private val wsConnections: ConcurrentHashMap<String, WsConnection>,
    private val activePeers: MutableList<ConnectedPeer>,
    private val trustStore: DeviceTrustStore,
    private val _status: MutableStateFlow<SyncStatusSnapshot>,
    private val appendDebug: (String) -> Unit,
    private val updateStatus: () -> Unit
) {

    // ---- Helpers ----

    fun buildDelta(since: Long, seq: Long): WsDelta {
        val deleted = repo.deletedIdsSince(since)
        return WsDelta(
        seq = seq,
        // Sender cursor (contract section b): the receiving side applies this
        // delta's tombstones with the cursor LWW rule instead of
        // unconditionally. The old default 0 meant "delete whenever absent"
        // and let an older sender wipe newer local edits.
        since = since,
        sessions = repo.sessions.value.filter { it.updatedAt > since },
        doses = repo.doses.value.filter { it.updatedAt > since },
        substances = repo.substances.value.filter { it.updatedAt > since },
        effects = repo.effects.value.filter { it.updatedAt > since },
        interactions = repo.interactions.value.filter { it.updatedAt > since },
        notes = repo.notes.value.filter { it.updatedAt > since },
        timelineEvents = repo.timelineEvents.value.filter { it.updatedAt > since },
        customUnits = repo.customUnits.value.filter { it.updatedAt > since },
        deletedSessionIds = deleted.deletedSessionIds,
        deletedDoseIds = deleted.deletedDoseIds,
        deletedNoteIds = deleted.deletedNoteIds,
        deletedSubstanceIds = deleted.deletedSubstanceIds,
        deletedEffectIds = deleted.deletedEffectIds,
        deletedInteractionIds = deleted.deletedInteractionIds,
        deletedTimelineEventIds = deleted.deletedTimelineEventIds,
        deletedCustomUnitIds = deleted.deletedCustomUnitIds
        )
    }

    fun isEmptyDelta(d: WsDelta): Boolean =
        d.sessions.isEmpty() && d.doses.isEmpty() && d.substances.isEmpty() &&
        d.effects.isEmpty() && d.interactions.isEmpty() && d.notes.isEmpty() &&
        d.timelineEvents.isEmpty() && d.customUnits.isEmpty() &&
        d.deletedSessionIds.isEmpty() && d.deletedDoseIds.isEmpty() &&
        d.deletedNoteIds.isEmpty() && d.deletedSubstanceIds.isEmpty() &&
        d.deletedEffectIds.isEmpty() && d.deletedInteractionIds.isEmpty() &&
        d.deletedTimelineEventIds.isEmpty() && d.deletedCustomUnitIds.isEmpty()

    // ===== WS Heartbeat & Incoming Reader =====

    /** Launch a coroutine that reads incoming WS frames: processes pongs, pings, deltas, acks. */
    fun launchIncomingReader(
        deviceId: String,
        session: WebSocketSession,
        wsConnection: WsConnection
    ): Job = backgroundScope.launch {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        val msg = wsJson.decodeFromString<WsMessage>(text)
                        when (msg) {
                            is WsPong -> {
                                wsConnection.lastPongSeq = msg.seq
                            }
                            is WsDelta -> {
                                // Receiving never advances lastSyncTime: that is
                                // the OUTGOING cursor and only moves when an ack
                                // confirms a push (wall-clock receive stamp
                                // removed per contract d). The delta applies its
                                // own sender cursor for the tombstone rule.
                                val skipped = validateAndApplyDelta(msg)
                                if (skipped > 0) {
                                    appendDebug("WS delta from $deviceId: $skipped invalid items skipped")
                                }
                            }
                            is WsAck -> handleWsAck(deviceId, wsConnection, msg)
                            is WsPing -> {
                                session.send(Frame.Text(wsJson.encodeToString(WsMessage.serializer(), WsPong(msg.seq))))
                            }
                        }
                    } catch (e: Exception) {
                        // Decode failures must be visible: a protocol mismatch
                        // (e.g. missing discriminator) silently killed the whole
                        // WS channel before 2026-07-31. Log, never crash.
                        appendDebug("WS frame decode failed: ${e.message ?: e::class.simpleName}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.withTag("SyncTransport").w { "Incoming reader for $deviceId error: ${e.message}" }
                triggerReconnect(deviceId)
            }
        }
    }

    /** Launch a heartbeat coroutine that sends WsPing every 30s and expects a WsPong within 10s. */
    fun launchHeartbeat(
        deviceId: String,
        session: WebSocketSession,
        wsConnection: WsConnection
    ): Job = backgroundScope.launch {
        var seq = 0L
        while (isActive) {
            try {
                seq++
                wsConnection.lastPongSeq = -1L
                session.send(Frame.Text(wsJson.encodeToString(WsMessage.serializer(), WsPing(seq = seq))))

                // Wait up to 10 seconds for a matching WsPong
                var waited = 0L
                while (waited < 10_000 && wsConnection.lastPongSeq < seq) {
                    delay(500)
                    waited += 500
                }

                if (wsConnection.lastPongSeq < seq) {
                    Log.withTag("SyncTransport").w { "Heartbeat timeout for $deviceId: no pong within 10s" }
                    appendDebug("Heartbeat timeout: no pong from $deviceId within 10s")
                    triggerReconnect(deviceId)
                    return@launch
                }

                delay(30_000L - waited.coerceAtMost(30_000))
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.withTag("SyncTransport").w { "Heartbeat error for $deviceId: ${e.message}" }
                    triggerReconnect(deviceId)
                }
                return@launch
            }
        }
    }

    // ===== WS Reconnection =====

    /**
     * Contract section a: a WsAck only advances the cursor when it reports
     * success for the OLDEST in-flight delta (each entry's cycleStart was
     * captured before its delta was built). A rejected delta logs through
     * appendDebug, leaves lastSyncTime untouched, and falls back to an HTTP
     * push cycle for that data.
     */
    private fun handleWsAck(deviceId: String, conn: WsConnection, ack: WsAck) {
        val error = ack.error
        if (error != null) {
            conn.pendingPushes.failAll()
            appendDebug("WS delta rejected by $deviceId: $error; cursor held, falling back to HTTP push")
            backgroundScope.launch {
                try {
                    val peer = trustStore.getPeerById(deviceId)
                    owner.syncWith(
                        DiscoveredPeer(
                            deviceId = peer?.deviceId ?: deviceId,
                            displayName = peer?.displayName ?: deviceId,
                            host = conn.peerHost,
                            port = conn.peerPort,
                            isTrusted = true,
                            fingerprint = conn.peerFingerprint
                        ),
                        continuous = false
                    )
                } catch (e: Exception) {
                    appendDebug("WS to HTTP fallback failed for $deviceId: ${e.message}")
                }
            }
            return
        }
        conn.pendingPushes.ack(ack.seq)?.let { cycleStart ->
            owner.lastSyncTime = maxOf(owner.lastSyncTime ?: 0L, cycleStart)
        }
    }

    /** Trigger reconnection to a WS peer with exponential backoff: 1s, 2s, 4s, 8s, 16s (capped 30s). Max 5 attempts. */
    private fun triggerReconnect(deviceId: String) {
        val conn = wsConnections[deviceId] ?: return
        val host = conn.peerHost
        val port = conn.peerPort
        val fingerprint = conn.peerFingerprint
        backgroundScope.launch {
            owner.stopContinuousSync(deviceId)

            for (attempt in 1..5) {
                val delayMs = (1000L * (1L shl (attempt - 1))).coerceAtMost(30_000)
                appendDebug("Reconnecting WS to $deviceId (attempt $attempt/5 in ${delayMs / 1000}s)")
                delay(delayMs)
                try {
                    val peer = trustStore.getPeerById(deviceId) ?: break
                    val dp = DiscoveredPeer(
                        deviceId = peer.deviceId,
                        displayName = peer.displayName,
                        host = host,
                        port = port,
                        isTrusted = true,
                        fingerprint = fingerprint
                    )
                    owner.startContinuousSync(dp)
                    if (wsConnections.containsKey(deviceId)) {
                        appendDebug("Reconnected to $deviceId after $attempt attempt(s)")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.withTag("SyncTransport").w { "Reconnect attempt $attempt for $deviceId failed: ${e.message}" }
                }
            }
            appendDebug("Failed to reconnect to $deviceId after 5 attempts")
            _status.value = _status.value.copy(lastError = "WS reconnect failed for $deviceId")
        }
    }

    // ===== Data Validation =====

    /** Validate entity timestamps via the shared EntityTimePolicy. */
    private fun isReasonableTimestamp(ts: Long): Boolean =
        EntityTimePolicy.isReasonableEntityTime(ts)

    /**
     * Apply a WsDelta with per-entity data validation.
     * Skips entities with blank IDs, unreasonable timestamps, or (for doses) non-finite/negative amounts.
     * Returns the count of skipped invalid items.
     */
    private fun validateAndApplyDelta(delta: WsDelta): Int {
        var skipped = 0

        val sessions = delta.sessions.filter { s ->
            val ok = s.id.isNotBlank() && isReasonableTimestamp(s.createdAt) && isReasonableTimestamp(s.updatedAt)
            if (!ok) skipped++; ok
        }
        val doses = delta.doses.filter { d ->
            val ok = d.id.isNotBlank() && isReasonableTimestamp(d.createdAt) && isReasonableTimestamp(d.updatedAt)
                    && d.amount.isFinite() && d.amount >= 0.0
            if (!ok) skipped++; ok
        }
        val substances = delta.substances.filter { s ->
            val ok = s.id.isNotBlank() && isReasonableTimestamp(s.createdAt) && isReasonableTimestamp(s.updatedAt)
            if (!ok) skipped++; ok
        }
        val effects = delta.effects.filter { e ->
            val ok = e.id.isNotBlank() && isReasonableTimestamp(e.createdAt) && isReasonableTimestamp(e.updatedAt)
            if (!ok) skipped++; ok
        }
        val interactions = delta.interactions.filter { i ->
            val ok = i.id.isNotBlank() && isReasonableTimestamp(i.createdAt) && isReasonableTimestamp(i.updatedAt)
            if (!ok) skipped++; ok
        }
        val notes = delta.notes.filter { n ->
            val ok = n.id.isNotBlank() && isReasonableTimestamp(n.createdAt) && isReasonableTimestamp(n.updatedAt)
            if (!ok) skipped++; ok
        }
        val timelineEvents = delta.timelineEvents.filter { t ->
            val ok = t.id.isNotBlank() && isReasonableTimestamp(t.createdAt) && isReasonableTimestamp(t.updatedAt)
            if (!ok) skipped++; ok
        }
        val customUnits = delta.customUnits.filter { u ->
            val ok = u.id.isNotBlank() && isReasonableTimestamp(u.createdAt) && isReasonableTimestamp(u.updatedAt)
            if (!ok) skipped++; ok
        }

        if (skipped > 0) {
            Log.withTag("SyncTransport").w { "Data validation: skipped $skipped invalid items in WS delta" }
        }

        fun ids(ids: List<String>): List<String> {
            val ok = ids.filter { id -> id.isNotBlank() && id.length <= 128 }
            skipped += ids.size - ok.size
            return ok
        }
        repo.applyBatch(
            sessions = sessions, doses = doses, substances = substances,
            effects = effects, interactions = interactions, notes = notes,
            timelineEvents = timelineEvents, customUnits = customUnits,
            lastWriterWins = true,
            deletedSessionIds = ids(delta.deletedSessionIds),
            deletedDoseIds = ids(delta.deletedDoseIds),
            deletedNoteIds = ids(delta.deletedNoteIds),
            deletedSubstanceIds = ids(delta.deletedSubstanceIds),
            deletedEffectIds = ids(delta.deletedEffectIds),
            deletedInteractionIds = ids(delta.deletedInteractionIds),
            deletedTimelineEventIds = ids(delta.deletedTimelineEventIds),
            deletedCustomUnitIds = ids(delta.deletedCustomUnitIds),
            // Contract section b: same cursor LWW rule as every other JVM
            // apply path. delta.since is the sender's cursor when it built
            // the delta; the 0 default from older senders collapses to the
            // conservative "no local entity" rule.
            tombstoneCutoff = delta.since
        )
        // Durability: persist what we just applied (audit D1).
        persistAfterApply?.invoke()
        return skipped
    }

    // ===== Stale Connection Cleanup =====

    private var staleCleanupJob: Job? = null

    /** Periodically (every 5 min) check for inactive WebSocket sessions and remove them. */
    fun startStaleCleanup() {
        if (staleCleanupJob?.isActive == true) return
        staleCleanupJob = backgroundScope.launch {
            while (isActive) {
                delay(5 * 60_000L) // every 5 minutes
                val toRemove = mutableListOf<String>()
                wsConnections.forEach { (deviceId, conn) ->
                    try {
                        if (!conn.session.isActive) {
                            toRemove.add(deviceId)
                        }
                    } catch (_: Exception) {
                        toRemove.add(deviceId)
                    }
                }
                toRemove.forEach { deviceId ->
                    Log.withTag("SyncTransport").w { "Stale WS connection to $deviceId: removing" }
                    appendDebug("Stale cleanup: removing connection to $deviceId")
                    wsConnections.remove(deviceId)?.let { conn ->
                        conn.mutationJob.cancel()
                        conn.heartbeatJob?.cancel()
                        conn.incomingJob?.cancel()
                        conn.client.close()
                    }
                    activePeers.removeAll { it.deviceId == deviceId }
                }
                if (toRemove.isNotEmpty()) updateStatus()
            }
        }
    }
}
