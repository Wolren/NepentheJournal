package app.journal.model
import kotlinx.serialization.Serializable

@Serializable
data class Effect(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    val name: String,
    val url: String? = null,
    val description: String? = null,
    val category: String? = null,
    val substanceIds: List<String> = emptyList()
) : VaultDocument
