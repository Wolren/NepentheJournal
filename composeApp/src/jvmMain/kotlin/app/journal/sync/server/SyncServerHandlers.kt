package app.journal.sync

import app.journal.data.IJournalRepository
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
import app.journal.sync.base64Decode
import app.journal.sync.base64Encode
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
        conflicts += applySessionsWithConflict(tagged.sessions, tagged.deletedSessionIds, tagged.deviceId)
        conflicts += applyNotesWithConflict(tagged.notes, tagged.deletedNoteIds, tagged.deviceId)
        onConnection(if (conflicts > 0) "$conflicts conflict(s)" else "Synced from ${tagged.deviceName}")
    }

    /**
     * Apply pushed sessions with the session-outcome conflict branch
     * (contract section c item 4: this branch may stay platform-side until
     * the shared mergeSessionConflict lands in commonMain). Loser bodies
     * become conflict notes; device names are truncated by the caller.
     * Returns the number of conflicts created.
     */
    fun applySessionsWithConflict(
        sessions: List<Session>,
        deletedIds: List<String>,
        remoteDeviceId: String
    ): Int {
        var conflicts = 0
        sessions.forEach { session ->
            if (session.id in deletedIds) return@forEach
            val existing = repo.getSession(session.id)
            if (existing != null && existing.updatedAt > session.updatedAt) {
                repo.upsertNote(Note(
                    id = "conflict:${session.id}:$remoteDeviceId",
                    sessionId = session.id,
                    title = "Sync conflict: ${session.title}",
                    body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                    createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    // Tag interaction provenance: conflict notes always carry
                    // the pushing device so the origin is never ambiguous.
                    deviceOrigin = "sync:$remoteDeviceId"
                ))
                conflicts++
            } else repo.upsertSession(session.copy(deviceOrigin = session.deviceOrigin.ifBlank { "sync:$remoteDeviceId" }))
        }
        return conflicts
    }

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
        conflictsCreated = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
        truncated = truncated,
        nextSince = if (truncated) lowWater.min() else 0L
    )
    }
}
