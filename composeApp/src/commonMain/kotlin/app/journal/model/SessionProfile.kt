package app.journal.model

import kotlinx.serialization.Serializable

/** Optional demographics attached to a session describing the person who took the substance(s). */
@Serializable
data class SessionProfile(
    val age: Int? = null,
    val gender: String? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null
)
