package app.journal.ui.pet

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import app.journal.util.PlatformFile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the companion sits inside the window. */
enum class PetCorner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** What the companion reacts to: a greeting on launch, a live session, or nothing in particular. */
enum class PetState { IDLE, RUNNING, WAVING }

/** A one-shot reaction the companion plays over its base state. */
enum class PetReaction(val clipName: String) { CHECK_IN("checkin"), FAILURE("fail") }

/** Off on first run: the companion appears only after the user switches it on. */
@Serializable
data class PetSettings(
    val enabled: Boolean = false,
    val slug: String = "nightly-fox",
    val scale: Float = 0.5f,
    val corner: PetCorner = PetCorner.BOTTOM_END,
)

/** One installed petdex set: a slug plus the name its manifest declares. */
data class PetInfo(val slug: String, val displayName: String)

/** A decoded spritesheet plus the clips it declares. */
@Immutable
class PetSheet(
    val image: ImageBitmap,
    val frameWidth: Int,
    val frameHeight: Int,
    val clips: List<PetClip>,
) {
    fun clip(name: String): PetClip? = clips.firstOrNull { it.name == name }
}

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

    private val _reactions = MutableSharedFlow<PetReaction>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One-shot reactions; the newest wins, so a burst of events plays one clip. */
    val reactions: SharedFlow<PetReaction> = _reactions.asSharedFlow()

    fun update(next: PetSettings) {
        _settings.value = next
        PetStore.save(next)
    }

    fun react(reaction: PetReaction) {
        _reactions.tryEmit(reaction)
    }

    companion object {
        val instance: PetManager by lazy { PetManager() }
    }
}
