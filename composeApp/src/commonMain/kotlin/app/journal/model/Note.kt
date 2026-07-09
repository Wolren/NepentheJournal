package app.journal.model
import kotlinx.serialization.Serializable

@Serializable
data class ConflictSibling(
    val body: String,
    val deviceOrigin: String,
    val updatedAt: Long
)

/**
 * Conflict: SIBLING_AND_FLAG
 * UI must render a conflict banner when conflictSiblings is non-empty.
 */
@Serializable
data class Note(
    override val id: String,
    override val docType: String = "note",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val sessionId: String? = null,
    val doseId: String? = null,
    val title: String? = null,
    val body: String,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val conflictSiblings: List<ConflictSibling> = emptyList()
) : VaultDocument
