package app.journal.model
import kotlinx.serialization.Serializable

enum class InteractionRisk { DANGEROUS, UNSAFE, UNCERTAIN, LOW, UNKNOWN }

/** Conflict: REMOTE_SOURCE_WINS. Query bidirectionally by substanceAId OR substanceBId. */
@Serializable
data class Interaction(
    override val id: String,
    override val docType: String = "interaction",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    val substanceAId: String,
    val substanceBId: String,
    val riskLevel: InteractionRisk,
    val description: String? = null,
    val sources: List<String> = emptyList()
) : VaultDocument
