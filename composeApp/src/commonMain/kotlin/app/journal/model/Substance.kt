package app.journal.model
import kotlinx.serialization.Serializable

/** Conflict: REMOTE_SOURCE_WINS + preserve userAnnotations */
@Serializable
data class Substance(
    override val id: String,
    override val docType: String = "substance",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String = "system",
    val pwikiId: String? = null,
    val name: String,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val substanceClass: List<String> = emptyList(),
    val routesOfAdministration: List<String> = emptyList(),
    val dosageBands: Map<String, String> = emptyMap(),
    val durationProfile: Map<String, String> = emptyMap(),
    val addictionPotential: String? = null,
    val toxicity: List<String> = emptyList(),
    val crossTolerances: List<String> = emptyList(),
    val cachedAt: Long,
    val sourceVersion: String,
    val userAnnotations: Map<String, String> = emptyMap()
) : VaultDocument
