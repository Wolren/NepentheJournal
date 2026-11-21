package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Custom dosing unit defined by the user.
 * Enables flexible units beyond mg/µg: drops, puffs, tabs, sprays, etc.
 */
@Immutable
@Serializable
data class CustomUnit(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    val substanceId: String,
    val name: String,
    val description: String? = null,
    /** When true, uses estimated dose display (e.g. "~2 puffs") */
    val isEstimate: Boolean = false
) : ClockStamped
