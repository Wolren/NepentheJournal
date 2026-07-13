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
    val sessions: List<Session> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList()
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
    classDiscriminator = "#type"
    serializersModule = wsModule
}
