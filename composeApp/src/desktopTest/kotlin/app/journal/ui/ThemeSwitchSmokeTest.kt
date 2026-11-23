package app.journal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.sync.SyncTransport
import app.journal.ui.settings.SettingsScreen
import app.journal.ui.theme.LocalSpacing
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.ThemeManager
import app.journal.ui.theme.ThemePresets
import app.journal.ui.theme.nepentheTypography
import app.journal.ui.theme.spacing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every preset click rebuilds the MaterialTheme under the settings tree, the
 * same path the phone takes; a null state read here throws instead of on device.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeSwitchSmokeTest {

    @Test
    fun switchingEveryPresetAndBaseThemeKeepsTheSettingsScreenComposed() = runComposeUiTest {
        val repo = JournalRepository()
        val dataDir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-theme-switch-${System.nanoTime()}"
        ).apply { mkdirs() }
        val engine = SyncTransport(repo, dataDir.absolutePath)
        val manager = ThemeManager.instance
        manager.update(ThemeManager.defaultConfig)

        setContent {
            val config by manager.config.collectAsState()
            CompositionLocalProvider(LocalThemeConfig provides config) {
                MaterialTheme(
                    colorScheme = manager.colorScheme(manager.isDarkTheme()),
                    typography = nepentheTypography(config.fontFamily, config.fontScale),
                    shapes = config.shapes
                ) {
                    CompositionLocalProvider(LocalSpacing provides config.density.spacing) {
                        SettingsScreen(repo = repo, syncEngine = engine)
                    }
                }
            }
        }
        waitForIdle()

        click("Manage")
        waitForIdle()

        for (base in listOf("Dark", "Light", "System", "Custom")) {
            click(base)
            waitForIdle()
            for (preset in ThemePresets.all) {
                click(preset.name)
                waitForIdle()
                // A click that misses the card would leave the palette untouched and prove nothing.
                val applied = manager.config.value.primaryColor
                assertTrue(
                    applied == preset.dark.primaryColor || applied == preset.light.primaryColor,
                    "clicking ${preset.name} must apply its palette, got $applied"
                )
            }
        }
        dataDir.deleteRecursively()
    }

    private fun ComposeUiTest.click(label: String) {
        val nodes = onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false)
        if (nodes.isEmpty()) return
        // The card is taller than the window, so the last presets need a scroll before they can be tapped.
        onAllNodesWithText(label).onFirst().performScrollTo().performClick()
    }
}
