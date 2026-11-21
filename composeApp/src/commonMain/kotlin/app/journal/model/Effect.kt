package app.journal.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Effect(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    val name: String,
    val url: String? = null,
    val description: String? = null,
    val category: String? = null,
    val substanceIds: List<String> = emptyList()
) : ClockStamped
