package app.journal.sync

import app.journal.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Messages exchanged over the continuous sync WebSocket.
 * Polymorphic serialization uses "#type" as the class discriminator key.
 */
@Serializable
sealed interface WsMessage {
    /** Monotonically increasing sequence number per-connection. */
    val seq: Long
}

@Serializable
@SerialName("ws:ping")
data class WsPing(override val seq: Long) : WsMessage

@Serializable
@SerialName("ws:pong")
data class WsPong(override val seq: Long) : WsMessage

@Serializable
@SerialName("ws:delta")
data class WsDelta(
    override val seq: Long,
    /**
     * Sender cursor this delta was built from (its lastSyncTime). Servers and
     * clients apply tombstones from this delta with the cursor LWW rule:
     * delete only when no local entity exists or local.updatedAt <= since
     * (contract section b). Defaults to 0 for wire compatibility with older
     * senders, which means "no local entity exists" only.
     */
    val since: Long = 0L,
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList(),
    val deletedSessionIds: List<String> = emptyList(),
    val deletedDoseIds: List<String> = emptyList(),
    val deletedNoteIds: List<String> = emptyList(),
    val deletedSubstanceIds: List<String> = emptyList(),
    val deletedEffectIds: List<String> = emptyList(),
    val deletedInteractionIds: List<String> = emptyList(),
    val deletedTimelineEventIds: List<String> = emptyList(),
    val deletedCustomUnitIds: List<String> = emptyList()
) : WsMessage

@Serializable
@SerialName("ws:ack")
data class WsAck(
    override val seq: Long,
    val error: String? = null
) : WsMessage

/** Serializers module that registers all WsMessage subtypes for polymorphic deserialization. */
val wsModule = SerializersModule {
    polymorphic(WsMessage::class) {
        subclass(WsPing::class)
        subclass(WsPong::class)
        subclass(WsDelta::class)
        subclass(WsAck::class)
    }
}

/** The JSON serialization instance used for WebSocket frames. */
val wsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "#type"
    serializersModule = wsModule
}
