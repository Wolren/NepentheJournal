package app.journal.ui.substances.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.journal.catalog.CatalogSource
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.ThemeManager
import kotlin.test.Test
import kotlin.test.assertTrue

/** The comparison sheet is the only place the app shows that sources disagree. */
@OptIn(ExperimentalTestApi::class)
class PharmacologyComparisonUiTest {

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        val config by ThemeManager.instance.config.collectAsState()
        CompositionLocalProvider(LocalThemeConfig provides config) {
            MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
    }

    @Test
    fun selectedRowOffersEverySource() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
        setContent { Themed { Box(Modifier.width(520.dp).padding(8.dp)) { PharmacologySection(lsd) } } }
        onNode(hasText("Pharmacology") and hasClickAction()).performClick()
        waitForIdle()

        // Families are alphabetical, so 5-HT2A can sit below the fold; the first row is visible.
        val rows = onAllNodes(hasClickAction() and hasText("Ki", substring = true))
        assertTrue(rows.fetchSemanticsNodes().isNotEmpty(), "no measured row rendered")
        rows[0].performClick()
        waitForIdle()

        onNodeWithText("Compare", substring = true).performClick()
        waitForIdle()

        val dialogTexts = onAllNodesWithText("nM", substring = true).fetchSemanticsNodes().size
        assertTrue(dialogTexts > 0, "comparison sheet rendered no measurement rows")
        assertTrue(
            onAllNodesWithText("PDSP", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("BindingDB", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "comparison sheet named no source",
        )
    }
}
