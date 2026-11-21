package app.journal.ui.theme

import app.journal.util.PlatformFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A half-written settings file must degrade to the default theme, never crash
 * the app and never block the next save.
 */
class ThemeStoreDurabilityTest {

    private fun themeFile(): File = File("${PlatformFile.dataDir()}/theme.json")

    @Test
    fun aGarbageThemeFileFallsBackToDefaultsAndTheNextSaveSticks() {
        val file = themeFile()
        val original = if (file.exists()) file.readText() else null
        try {
            file.parentFile?.mkdirs()
            file.writeText("{ this is not a theme")
            assertNull(ThemeStore.load(), "a corrupt theme must decode to null, never throw")

            val config = ThemeDefaults.Dark
            ThemeStore.save(config)
            assertEquals(config, ThemeStore.load(), "the next save must overwrite the corrupt file")
        } finally {
            if (original != null) file.writeText(original) else file.delete()
        }
    }

    @Test
    fun aTruncatedThemeFileDoesNotResurrectOldValues() {
        val file = themeFile()
        val original = if (file.exists()) file.readText() else null
        try {
            file.parentFile?.mkdirs()
            file.writeText("""{"baseTheme":"LIGHT","primaryColor":4294901760""")
            assertNull(ThemeStore.load(), "a truncated body must decode to null")
        } finally {
            if (original != null) file.writeText(original) else file.delete()
        }
    }
}
