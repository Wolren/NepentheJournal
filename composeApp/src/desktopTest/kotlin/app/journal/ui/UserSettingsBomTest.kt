package app.journal.ui

import app.journal.ui.keybind.BindableKey
import app.journal.ui.keybind.Keybind
import app.journal.ui.keybind.KeybindSettings
import app.journal.ui.keybind.KeybindStore
import app.journal.ui.pet.PetSettings
import app.journal.ui.pet.PetStore
import app.journal.util.PlatformFile
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * User-editable settings files survive a UTF-8 BOM. Notepad and Excel add one
 * when they save, and a BOM silently reset the user's shortcuts and companion.
 */
class UserSettingsBomTest {

    private val home = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-settings-bom-${System.nanoTime()}")
    private var previousHome: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun useTempHome(): File {
        home.mkdirs()
        if (previousHome == null) previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        return File(PlatformFile.dataDir()).apply { mkdirs() }
    }

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
        home.deleteRecursively()
    }

    @Test
    fun keybindsLoadWithABom() {
        val dir = useTempHome()
        val expected = KeybindSettings(search = Keybind(BindableKey.Q, ctrl = true, shift = true))
        File(dir, "keybinds.json").writeText(
            "\uFEFF" + json.encodeToString(KeybindSettings.serializer(), expected),
        )

        assertEquals(expected, KeybindStore.load(), "a BOM must not reset the user's shortcuts")
    }

    @Test
    fun petSettingsLoadWithABom() {
        val dir = useTempHome()
        val expected = PetSettings(enabled = true, slug = "custom-pet")
        File(dir, "pet.json").writeText(
            "\uFEFF" + json.encodeToString(PetSettings.serializer(), expected),
        )

        assertEquals(expected, PetStore.load(), "a BOM must not reset the user's companion")
    }
}
