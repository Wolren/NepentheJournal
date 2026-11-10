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

    fun encode(config: ThemeConfig): String = json.encodeToString(ThemeConfig.serializer(), config)

    fun decode(text: String): ThemeConfig? = try {
        json.decodeFromString(ThemeConfig.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun loadPresets(): List<ThemePreset> = try {
        val file = "${PlatformFile.dataDir()}/theme-presets.json"
        if (PlatformFile.size(file) < 0) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ThemePreset.serializer()), PlatformFile.readText(file))
    } catch (_: Exception) {
        emptyList()
    }

    fun savePresets(presets: List<ThemePreset>) {
        try {
            val file = "${PlatformFile.dataDir()}/theme-presets.json"
            PlatformFile.writeText(file, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ThemePreset.serializer()), presets))
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
