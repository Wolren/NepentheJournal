package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Custom dosing unit defined by the user.
 * Enables flexible units beyond mg/µg: drops, puffs, tabs, sprays, etc.
 */
@Serializable
data class CustomUnit(
    override val id: String,
    override val docType: String = "customUnit",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val substanceId: String,
    val name: String,
    val description: String? = null,
    /** Estimated mg-equivalent for tolerance estimation (nullable = unknown) */
    val estimatedMgPerUnit: Double? = null,
    /** When true, uses estimated dose display (e.g. "~2 puffs") */
    val isEstimate: Boolean = false
) : VaultDocument
