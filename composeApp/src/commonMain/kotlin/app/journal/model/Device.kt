package app.journal.model
import kotlinx.serialization.Serializable

enum class TrustLevel  { TRUSTED, REVOKED, PENDING }
enum class DiscoveryMode { MANUAL, LAN_AUTO_DISCOVERY, HYBRID }

/** Conflict: LOCAL_WINS_NEVER_MERGE. Never replicate Device docs to peers */
@Serializable
data class Device(
    override val id: String,
    override val docType: String = "device",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val displayName: String,
    val platform: String,
    val fingerprint: String,
    val trustLevel: TrustLevel = TrustLevel.TRUSTED,
    val lastSyncAt: Long? = null,
    val pairedAt: Long
) : VaultDocument
