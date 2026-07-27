package app.journal.model
import kotlinx.serialization.Serializable

/** Conflict: SET_UNION_MERGE. Union of edges, deduplicate by (fromId, toId, type) */
@Serializable
data class Link(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val fromId: String,
    val toId: String,
    val type: String,
    val label: String? = null,
    val weight: Float = 1.0f
) : VaultDocument
