package app.journal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The activity grid must show a visible difference between empty days and
 * active days on every scheme, including schemes whose variants sit close.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityRampContrastTest {

    private fun levelsFor(scheme: androidx.compose.material3.ColorScheme): List<Color> {
        var captured: List<Color> = emptyList()
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = scheme) {
                    captured = activityLevelColors()
                }
            }
            waitForIdle()
        }
        return captured
    }

    private fun assertRamp(levels: List<Color>, scheme: androidx.compose.material3.ColorScheme) {
        assertTrue(levels.size == 5, "expected empty tone plus four levels, got ${levels.size}")
        val card = blend(scheme.background, scheme.surfaceVariant, 1f)
        val empty = contrastRatio(levels[0], card)
        assertTrue(empty >= 1.30f, "empty cell vs card only ${"%.2f".format(empty)}:1")
        levels.zipWithNext().forEachIndexed { index, (low, high) ->
            val ratio = contrastRatio(low, high)
            assertTrue(ratio >= 1.18f, "level ${index + 1} vs level $index only ${"%.2f".format(ratio)}:1")
        }
    }

    @Test
    fun darkSchemeRampSeparates() {
        assertRamp(levelsFor(darkColorScheme()), darkColorScheme())
    }

    @Test
    fun lightSchemeRampSeparates() {
        assertRamp(levelsFor(lightColorScheme()), lightColorScheme())
    }

    @Test
    fun flatSchemeRampSeparates() {
        val flat = darkColorScheme(
            background = Color(0xFF161A16),
            surface = Color(0xFF161A16),
            surfaceVariant = Color(0xFF191D19),
            onSurfaceVariant = Color(0xFF2A2F2A),
            primary = Color(0xFF3B6B3B),
        )
        assertRamp(levelsFor(flat), flat)
    }
}
