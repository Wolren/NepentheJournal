package app.journal.model
import kotlinx.serialization.Serializable

/** Conflict: APPEND_ONLY — device-keyed IDs prevent structural conflicts */
@Serializable
data class Dose(
    override val id: String,
    override val docType: String = "dose",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val sessionId: String,
    val substanceId: String,
    val routeOfAdministration: String,
    val amount: Double,
    val unit: String,
    val timestamp: Long,
    val redosing: Boolean = false,
    val notes: String? = null
) : VaultDocument
