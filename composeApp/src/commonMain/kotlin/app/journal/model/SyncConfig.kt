package app.journal.model
import kotlinx.serialization.Serializable

/**
 * Conflict: LOCAL_WINS_NEVER_MERGE
 * Filter out of outbound replication. Never push SyncConfig to peers.
 */
@Serializable
data class SyncConfig(
    override val id: String,
    override val docType: String = "syncConfig",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val deviceId: String,
    val displayName: String,
    val hostingEnabled: Boolean = false,
    val listenerPort: Int = 4984,
    val autoSyncOnLan: Boolean = true,
    val continuousSync: Boolean = false,
    val discoveryMode: DiscoveryMode = DiscoveryMode.LAN_AUTO_DISCOVERY,
    val enableDeltaSync: Boolean = true,
    val syncCollections: List<String> = listOf(
        "sessions", "doses", "timelineEvents", "notes",
        "substances", "effects", "interactions",
        "links", "persons", "attachments", "devices"
        // syncConfigs deliberately excluded
    )
) : VaultDocument
