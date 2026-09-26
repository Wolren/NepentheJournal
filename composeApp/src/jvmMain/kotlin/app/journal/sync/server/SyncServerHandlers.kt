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

    /**
     * Oldest-first pages with a COMPOSITE (updatedAt, id) low-water cursor.
     *
     * The id half exists because a single timestamp cannot resume inside a
     * group of tied timestamps: the bundled seed's 2015 interactions share
     * ONE updatedAt, so the previous `updatedAt > since` filter served 100
     * rows of that group, reported nextSince = T, and then returned zero
     * rows forever — every row past page one was silently unreachable.
     * Sorting by (updatedAt, id) makes the order total, so `take(max)` makes
     * progress within a tie group and resuming exactly after the cut row
     * neither skips nor loops (see [isAfterPullCursor]).
     *
     * Cursor semantics by [sinceId]:
     *  - blank: wall-clock cycle cursor, inclusive `>=` (an entity stamped in
     *    the cursor's millisecond but missed last cycle is re-served, which
     *    is idempotent, instead of skipped, which is invisible);
     *  - present: strictly-after the (since, sinceId) pair.
     *
     * Low-water across types: the SMALLEST cut pair (lexicographic in
     * (updatedAt, id)) becomes nextSince/nextSinceId. Types cut later then
     * re-serve rows the client already saw (harmless LWW duplicates), while
     * no type can sit behind the shared cursor.
     */
    fun handlePull(since: Long, sinceId: String = ""): SyncResponse {
        val deleted = repo.deletedIdsSince(since)
        var truncated = false
        val cuts = mutableListOf<Pair<Long, String>>()
        fun <T> page(items: List<T>, max: Int, id: (T) -> String, updatedAt: (T) -> Long): List<T> {
            val fresh = items
                .filter { isAfterPullCursor(updatedAt(it), id(it), since, sinceId) }
                .sortedWith(compareBy({ updatedAt(it) }, { id(it) }))
            if (fresh.size <= max) return fresh
            truncated = true
            val cut = fresh.take(max)
            val last = cut.last()
            cuts += updatedAt(last) to id(last)
            return cut
        }
        val response = SyncResponse(
        success = true,
        sessions = page(repo.sessions.value, SyncLimits.MAX_ITEMS_DEFAULT, { it.id }) { it.updatedAt },
        doses = page(repo.doses.value, SyncLimits.MAX_ITEMS_DEFAULT, { it.id }) { it.updatedAt },
        substances = page(repo.substances.value, SyncLimits.MAX_SUBSTANCES, { it.id }) { it.updatedAt },
        interactions = page(repo.interactions.value, SyncLimits.MAX_INTERACTIONS, { it.id }) { it.updatedAt },
        notes = page(repo.notes.value, SyncLimits.MAX_ITEMS_DEFAULT, { it.id }) { it.updatedAt },
        timelineEvents = page(repo.timelineEvents.value, SyncLimits.MAX_ITEMS_DEFAULT, { it.id }) { it.updatedAt },
        effects = page(repo.effects.value, SyncLimits.MAX_EFFECTS, { it.id }) { it.updatedAt },
        customUnits = page(repo.customUnits.value, SyncLimits.MAX_CUSTOM_UNITS, { it.id }) { it.updatedAt },
        deletedSessionIds = deleted.deletedSessionIds,
        deletedDoseIds = deleted.deletedDoseIds,
        deletedNoteIds = deleted.deletedNoteIds,
        deletedSubstanceIds = deleted.deletedSubstanceIds,
        deletedEffectIds = deleted.deletedEffectIds,
        deletedInteractionIds = deleted.deletedInteractionIds,
        deletedTimelineEventIds = deleted.deletedTimelineEventIds,
        deletedCustomUnitIds = deleted.deletedCustomUnitIds,
        truncated = truncated
    )
        if (truncated) {
            // Lexicographic minimum over the per-type cut positions; cuts is
            // non-empty because truncated is only set inside page() right
            // before a cut is recorded (minWith throws otherwise, which
            // would be a bug in page(), not a protocol condition).
            val minCut = cuts.minWith(compareBy({ it.first }, { it.second }))
            return response.copy(nextSince = minCut.first, nextSinceId = minCut.second)
        }
        return response
    }
}
