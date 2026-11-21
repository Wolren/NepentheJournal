package app.journal.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class InteractionRisk { DANGEROUS, UNSAFE, UNCERTAIN, LOW, UNKNOWN }

@Immutable
@Serializable
data class Interaction(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    val substanceAId: String,
    val substanceBId: String,
    val riskLevel: InteractionRisk,
    val description: String? = null,
    val sources: List<String> = emptyList()
) : ClockStamped
