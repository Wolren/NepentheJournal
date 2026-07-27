package app.journal.model

interface VaultDocument {
    val id: String
    val createdAt: Long
    val updatedAt: Long
    val deviceOrigin: String
}
