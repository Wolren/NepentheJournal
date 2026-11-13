package app.journal.ui.pet

import kotlinx.serialization.Serializable

/** One animation: a sheet row, a frame count, and how it plays. */
@Serializable
data class PetClip(
    val name: String,
    val row: Int,
    val frames: Int,
    val fps: Float = 6.5f,
    val loop: Boolean = true,
    val restMs: Long = 3000,
)

/** A pet's own manifest. An empty [clips] list means the petdex grid that every set ships. */
@Serializable
data class PetManifest(
    val id: String = "",
    val displayName: String = "",
    val spritesheetPath: String = "spritesheet.webp",
    val frameWidth: Int = 192,
    val frameHeight: Int = 208,
    val clips: List<PetClip> = emptyList(),
) {
    fun resolvedClips(): List<PetClip> = if (clips.isEmpty()) LegacyClips else clips

    /** Looks a clip up by name, ignoring case; null when the pet does not declare it. */
    fun clip(name: String): PetClip? {
        val wanted = name.lowercase()
        return resolvedClips().firstOrNull { it.name == wanted }
    }
}

/** Frame counts of the 8 by 9 petdex grid, in row order. */
val LegacyRowFrames: List<Int> = listOf(6, 8, 8, 4, 5, 8, 6, 6, 6)

/** The petdex grid named the way this app plays it; rows 5 and 8 drive the two reactions. */
val LegacyClips: List<PetClip> = listOf(
    PetClip("idle", 0, 6, fps = 6.5f, restMs = 3400),
    PetClip("walk", 1, 8, fps = 8f),
    PetClip("gallop", 2, 8, fps = 10f),
    PetClip("wave", 3, 4, fps = 6.5f, restMs = 2000),
    PetClip("jump", 4, 5, fps = 8f, loop = false),
    PetClip("fail", 5, 8, fps = 6f, loop = false),
    PetClip("wait", 6, 6, fps = 5f),
    PetClip("run", 7, 6, fps = 11f, restMs = 1400),
    PetClip("checkin", 8, 6, fps = 6f, loop = false),
)
