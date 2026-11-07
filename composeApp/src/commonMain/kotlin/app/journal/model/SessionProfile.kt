package app.journal.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionProfile(
    val age: Int? = null,
    val gender: String? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null
)
