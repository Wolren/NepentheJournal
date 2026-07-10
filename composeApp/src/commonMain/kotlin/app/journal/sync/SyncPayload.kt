package app.journal.sync

import app.journal.model.*
import kotlinx.serialization.Serializable

/**
 * Wire format for P2P sync over HTTP.
 * Both push (client → host) and pull (host → client) use this shape.
 */
@Serializable
data class SyncBatch(
    val deviceId: String,
    val deviceName: String,
    val since: Long,
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList()
)

@Serializable
data class SyncResponse(
    val success: Boolean,
    val error: String? = null,
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val conflictsCreated: Int = 0
)

@Serializable
data class HostInfo(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val protocolVersion: Int = 1
)
