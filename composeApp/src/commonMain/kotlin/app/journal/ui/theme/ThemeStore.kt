package app.journal.ui.theme

import app.journal.util.PlatformFile
import kotlinx.serialization.json.Json

/** Persists the theme so a restart keeps the chosen palette and typography. */
object ThemeStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun path(): String = "${PlatformFile.dataDir()}/theme.json"

    fun load(): ThemeConfig? = try {
        if (PlatformFile.size(path()) < 0) null
        else json.decodeFromString(ThemeConfig.serializer(), PlatformFile.readText(path()))
    } catch (_: Exception) {
        null
    }

    fun save(config: ThemeConfig) {
        try {
            PlatformFile.writeText(path(), json.encodeToString(ThemeConfig.serializer(), config))
        } catch (_: Exception) {
            // Best effort: a theme that cannot persist must not break the app.
        }
    }
}
