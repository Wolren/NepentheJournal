package app.journal.ui.theme

import app.journal.util.PlatformFile
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A theme file edited by Notepad or Excel carries a UTF-8 BOM. It is still a
 * valid theme document, so a BOM must not silently reset every user choice.
 */
class ThemeStoreBomTest {

    private val home = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-theme-bom-${System.nanoTime()}")
    private var previousHome: String? = null

    private fun useTempHome() {
        home.mkdirs()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
        home.deleteRecursively()
    }

    @Test
    fun aThemeFileWithAUtf8BomStillLoads() {
        useTempHome()
        val config = ThemeDefaults.Dark.copy(backgroundImagePath = "bom-theme")
        val file = File("${PlatformFile.dataDir()}/theme.json")
        file.parentFile?.mkdirs()
        file.writeText("\uFEFF" + ThemeStore.encode(config))

        assertEquals(config, ThemeStore.load(), "a UTF-8 BOM must not reset the user's theme")
    }

    @Test
    fun aBomPrefixedPresetListStillLoads() {
        useTempHome()
        val preset = ThemePresets.all.first()
        val file = File("${PlatformFile.dataDir()}/theme-presets.json")
        file.parentFile?.mkdirs()
        val encoded = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ThemePreset.serializer()),
            listOf(preset),
        )
        file.writeText("\uFEFF$encoded")

        val loaded = ThemeStore.loadPresets()
        assertNotNull(loaded.firstOrNull { it.name == preset.name }, "a BOM must not hide saved presets: $loaded")
    }
}
