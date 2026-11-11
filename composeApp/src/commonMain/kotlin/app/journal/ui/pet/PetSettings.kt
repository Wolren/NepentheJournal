package app.journal.ui.pet

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import app.journal.util.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the companion sits inside the window. */
enum class PetCorner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** What the companion reacts to: a greeting on launch, a live session, or nothing in particular. */
enum class PetState { IDLE, RUNNING, WAVING }

@Serializable
data class PetSettings(
    val enabled: Boolean = true,
    val slug: String = "nightly-fox",
    val scale: Float = 0.5f,
    val corner: PetCorner = PetCorner.BOTTOM_END,
)

/** One installed petdex set: a slug plus the name its manifest declares. */
data class PetInfo(val slug: String, val displayName: String)

/** A decoded spritesheet. Rows are the petdex states, columns are animation frames. */
@Immutable
class PetSheet(
    val image: ImageBitmap,
    val frameWidth: Int,
    val frameHeight: Int,
    val rowFrames: List<Int>,
)

/** Installed pets, read from the petdex folders on desktop; empty where the platform has none. */
expect fun availablePets(): List<PetInfo>

/** Decodes one pet's spritesheet, or null when the slug is missing. */
expect fun loadPetSheet(slug: String): PetSheet?

/** Persists the companion choice so a restart keeps the same pet and corner. */
object PetStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun path(): String = "${PlatformFile.dataDir()}/pet.json"

    fun load(): PetSettings = try {
        if (PlatformFile.size(path()) < 0) PetSettings()
        else json.decodeFromString(PetSettings.serializer(), PlatformFile.readText(path()))
    } catch (_: Exception) {
        PetSettings()
    }

    fun save(settings: PetSettings) {
        try {
            PlatformFile.writeText(path(), json.encodeToString(PetSettings.serializer(), settings))
        } catch (_: Exception) {
            // Best effort: a companion that cannot persist must not break the app.
        }
    }
}

class PetManager private constructor() {
    private val _settings = MutableStateFlow(PetStore.load())
    val settings: StateFlow<PetSettings> = _settings.asStateFlow()

    fun update(next: PetSettings) {
        _settings.value = next
        PetStore.save(next)
    }

    companion object {
        val instance: PetManager by lazy { PetManager() }
    }
}
