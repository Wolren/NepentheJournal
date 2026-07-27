package app.journal.model
import kotlinx.serialization.Serializable

enum class PersonRole { PARTICIPANT, SITTER, OBSERVER }

/** Conflict: FIELD_LEVEL_MERGE */
@Serializable
data class Person(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val displayName: String,
    val role: PersonRole = PersonRole.PARTICIPANT,
    val contactInfo: String? = null,
    val notes: String? = null,
    val linkedSessionIds: List<String> = emptyList()
) : VaultDocument
