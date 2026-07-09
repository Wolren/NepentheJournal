package app.journal.model
import kotlinx.serialization.Serializable

/** Conflict: REMOTE_SOURCE_WINS */
@Serializable
data class Effect(
    override val id: String,
    override val docType: String = "effect",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    val name: String,
    val url: String? = null,
    val description: String? = null,
    val category: String? = null,
    val substanceIds: List<String> = emptyList()
) : VaultDocument
