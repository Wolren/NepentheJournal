package app.journal.model
import kotlinx.serialization.Serializable

/** Conflict: LOCAL_WINS_RETAIN_BOTH */
@Serializable
data class Attachment(
    override val id: String,
    override val docType: String = "attachment",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val sessionId: String? = null,
    val noteId: String? = null,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val blobRef: String,
    val contentHash: String,
    val caption: String? = null
) : VaultDocument
