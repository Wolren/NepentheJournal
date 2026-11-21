package app.journal.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
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
@Immutable
@Serializable
data class Note(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    val sessionId: String? = null,
    val doseId: String? = null,
    val title: String? = null,
    val body: String,
    val conflictSiblings: List<ConflictSibling> = emptyList()
) : ClockStamped
