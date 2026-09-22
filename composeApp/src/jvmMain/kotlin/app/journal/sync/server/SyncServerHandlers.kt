package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.data.PeerSyncWatermarks
import app.journal.serde.AppJson
import app.journal.log.Log
import app.journal.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.journal.sync.aesEncryptionKey
import app.journal.util.crypto.base64Decode
import app.journal.util.crypto.base64Encode
import app.journal.sync.decryptBody
import app.journal.sync.encryptBody
import java.util.concurrent.ConcurrentHashMap
internal class SyncServerHandlers(
    private val repo: IJournalRepository,
    private val onConnection: (String) -> Unit,
    private val persistAfterApply: (() -> Unit)?
) {

    fun handlePush(batch: SyncBatch) {
        var conflicts = 0
        val tagged = batch.copy(deviceName = batch.deviceName.take(200))
        // Peer-aware tombstone retention: batch.since is the SENDER's sync
        // cursor (contract sections b/d), i.e. this peer's high-water mark
        // of settled data. Recorded only after the router authenticated the
        // caller and proved batch.deviceId == caller (SyncServerRouting push
        // route), so the device id is trustworthy. Recording happens before
        // apply only as bookkeeping: a stale/low cursor can only make
        // pruning MORE conservative, never less.
        PeerSyncWatermarks.recordPeerCursor(tagged.deviceId, tagged.since)
        repo.applyBatch(
            substances = tagged.substances,
            doses = tagged.doses,
            interactions = tagged.interactions,
            timelineEvents = tagged.timelineEvents,
            effects = tagged.effects,
            customUnits = tagged.customUnits,
            lastWriterWins = true,
            deletedSessionIds = tagged.deletedSessionIds,
            deletedDoseIds = tagged.deletedDoseIds,
            deletedNoteIds = tagged.deletedNoteIds,
            deletedSubstanceIds = tagged.deletedSubstanceIds,
            deletedEffectIds = tagged.deletedEffectIds,
            deletedInteractionIds = tagged.deletedInteractionIds,
            deletedTimelineEventIds = tagged.deletedTimelineEventIds,
            deletedCustomUnitIds = tagged.deletedCustomUnitIds,
            tombstoneCutoff = batch.since
        )
        // Sessions and notes are NOT handed to applyBatch: both go through
        // the shared merge routes below, exactly like the WS delta path.
        // Sessions route through the ONE commonMain mergeSessionConflict
        // (contract section c item 4); both hosts call it.
        conflicts += mergeSessionConflict(repo, tagged.sessions, tagged.deletedSessionIds, tagged.deviceId)
        conflicts += applyNotesWithConflict(tagged.notes, tagged.deletedNoteIds, tagged.deviceId)
        onConnection(if (conflicts > 0) "$conflicts conflict(s)" else "Synced from ${tagged.deviceName}")
    }

    /**
     * Thin forwarder to the shared [mergeSessionConflict]. The logic lives
     * ONLY in commonMain now (both former platform copies deleted); this
     * delegate exists solely for the WS delta call site in
     * SyncServerRouting.kt (a file outside this change's ownership), which
     * passes discrete delta fields instead of a [SyncBatch].
     * Returns the number of conflicts created.
     */
    fun applySessionsWithConflict(
        sessions: List<Session>,
        deletedIds: List<String>,
        remoteDeviceId: String
    ): Int = mergeSessionConflict(repo, sessions, deletedIds, remoteDeviceId)

    /**
     * Apply incoming notes through the SHARED conflict merge
     * (IJournalRepository.upsertNoteWithConflict), never a hand-rolled
     * platform branch (contract section c). Shared by the HTTP push route
     * and the WS delta route so both paths resolve conflicts identically.
     * Returns the number of notes that came back with conflict siblings.
     */
    fun applyNotesWithConflict(
        notes: List<Note>,
        deletedIds: List<String>,
        remoteDeviceId: String
    ): Int {
        var conflicts = 0
        notes.forEach { note ->
            if (note.id in deletedIds) return@forEach
            val resolved = repo.upsertNoteWithConflict(note, remoteDeviceId)
            if (resolved != null && resolved.conflictSiblings.isNotEmpty()) conflicts++
        }
        return conflicts
    }

    fun handlePull(since: Long): SyncResponse {
        val deleted = repo.deletedIdsSince(since)
        // Oldest-first pages with a low-water nextSince: dropping the newest
        // (takeLast) would skip the dropped entities forever once the client
        // advances its cursor past them.
        val lowWater = mutableListOf<Long>()
        var truncated = false
        fun <T> page(items: List<T>, max: Int, updatedAt: (T) -> Long): List<T> {
            val fresh = items.filter { updatedAt(it) > since }.sortedBy(updatedAt)
            if (fresh.size <= max) return fresh
            truncated = true
            val cut = fresh.take(max)
            lowWater.add(cut.maxOf(updatedAt))
            return cut
        }
        return SyncResponse(
        success = true,
        sessions = page(repo.sessions.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
        doses = page(repo.doses.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
        substances = page(repo.substances.value, SyncLimits.MAX_SUBSTANCES) { it.updatedAt },
        interactions = page(repo.interactions.value, SyncLimits.MAX_INTERACTIONS) { it.updatedAt },
        notes = page(repo.notes.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
        timelineEvents = page(repo.timelineEvents.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
        effects = page(repo.effects.value, SyncLimits.MAX_EFFECTS) { it.updatedAt },
        customUnits = page(repo.customUnits.value, SyncLimits.MAX_CUSTOM_UNITS) { it.updatedAt },
        deletedSessionIds = deleted.deletedSessionIds,
        deletedDoseIds = deleted.deletedDoseIds,
        deletedNoteIds = deleted.deletedNoteIds,
        deletedSubstanceIds = deleted.deletedSubstanceIds,
        deletedEffectIds = deleted.deletedEffectIds,
        deletedInteractionIds = deleted.deletedInteractionIds,
        deletedTimelineEventIds = deleted.deletedTimelineEventIds,
        deletedCustomUnitIds = deleted.deletedCustomUnitIds,
        truncated = truncated,
        nextSince = if (truncated) lowWater.min() else 0L
    )
    }
}
