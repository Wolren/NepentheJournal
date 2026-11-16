package app.journal.ui.substances.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.journal.catalog.CatalogSource
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.ThemeConfig
import app.journal.ui.theme.ThemeManager
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pharmacology card must render measured values, not just its empty shell.
 * Opening a row is the only path that shows a number now, so the tap is part of the contract.
 */
@OptIn(ExperimentalTestApi::class)
class PharmacologyUiTest {

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        val config by ThemeManager.instance.config.collectAsState()
        CompositionLocalProvider(LocalThemeConfig provides config) {
            if (dark) MaterialTheme(colorScheme = darkColorScheme()) { content() } else MaterialTheme { content() }
        }
    }

    private fun withRestoredTheme(block: (ThemeConfig) -> Unit) {
        val original = ThemeManager.instance.config.value
        try {
            block(original)
        } finally {
            ThemeManager.instance.update(original)
        }
    }

    @Test
    fun lsdRendersMeasuredAffinities() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
        setContent { Themed { PharmacologySection(lsd) } }

        onNode(hasText("Pharmacology") and hasClickAction()).performClick()
        waitForIdle()

        val rows = onAllNodes(hasText("5-HT2A", substring = true) and hasClickAction())
        assertTrue(rows.fetchSemanticsNodes().isNotEmpty(), "5-HT2A row missing from the dot plot")
        // Families are alphabetical, so 5-HT2A can sit below the fold; click a visible row.
        val measured = onAllNodes(hasClickAction() and hasText("Ki", substring = true))
        assertTrue(measured.fetchSemanticsNodes().isNotEmpty(), "no measured row rendered")
        measured[0].performClick()
        waitForIdle()

        val texts = onAllNodesWithText("nM", substring = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
        assertTrue(
            texts.any { Regex("""\d\s?nM""").containsMatchIn(it) },
            "selected row revealed no numeric value, saw: $texts",
        )
    }

    @Test
    fun barChartViewRendersWhenSelected() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        withRestoredTheme { original ->
            ThemeManager.instance.update(original.copy(pharmacologyChart = app.journal.ui.theme.PharmacologyChart.GROUPED))
            val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
            setContent { Themed { Box(Modifier.width(430.dp)) { PharmacologySection(lsd) } } }
            onNode(hasText("Pharmacology") and hasClickAction()).performClick()
            waitForIdle()
            onNode(hasContentDescription("Bar chart view")).performClick()
            waitForIdle()
            onNodeWithText("hover or click a bar", substring = true).assertExists()
            val cells = onAllNodes(hasClickAction() and hasText("5-HT2A", substring = true))
            cells[0].performClick()
            waitForIdle()
            onNodeWithText("median", substring = true).assertExists()
        }
    }

    @Test
    fun actionModeRenders() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        withRestoredTheme { original ->
            ThemeManager.instance.update(original.copy(pharmacologyChart = app.journal.ui.theme.PharmacologyChart.ACTION))
            val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
            setContent { Themed { Box(Modifier.width(430.dp)) { PharmacologySection(lsd) } } }
            onNode(hasText("Pharmacology") and hasClickAction()).performClick()
            waitForIdle()
            // The action column can repeat the group word, so this asserts presence, not one node.
            assertTrue(
                onAllNodesWithText("Agonist", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "action view rendered no agonist group",
            )
        }
    }

    @Test
    fun capturesActionChart() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        withRestoredTheme { original ->
            ThemeManager.instance.update(original.copy(pharmacologyChart = app.journal.ui.theme.PharmacologyChart.ACTION))
            val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
            setContent {
                Themed(dark = true) {
                    Box(Modifier.width(900.dp).padding(8.dp)) { PharmacologySection(lsd) }
                }
            }
            onNode(hasText("Pharmacology") and hasClickAction()).performClick()
            waitForIdle()
            val out = File(System.getProperty("java.io.tmpdir"), "pharmacology_action.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            println("captured: ${out.absolutePath}")
        }
    }

    @Test
    fun capturesBarChart() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        withRestoredTheme { original ->
            ThemeManager.instance.update(original.copy(pharmacologyChart = app.journal.ui.theme.PharmacologyChart.BARS))
            val lsd = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
            setContent {
                Themed(dark = true) {
                    Box(Modifier.width(900.dp).padding(8.dp)) { PharmacologySection(lsd) }
                }
            }
            onNode(hasText("Pharmacology") and hasClickAction()).performClick()
            waitForIdle()
            val out = File(System.getProperty("java.io.tmpdir"), "pharmacology_bars.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            println("captured: ${out.absolutePath}")
        }
    }

    @Test
    fun capturesDotPlot() = runComposeUiTest {
        if (System.getProperty("nepenthe.catalog") == null) return@runComposeUiTest
        withRestoredTheme { original ->
            ThemeManager.instance.update(original.copy(pharmacologyChart = app.journal.ui.theme.PharmacologyChart.GROUPED))
            val substance = CatalogSource.loadSubstances().first { it.id == "cid:5761" }
            setContent {
                Themed(dark = true) {
                    Box(Modifier.width(430.dp).padding(8.dp)) { PharmacologySection(substance) }
                }
            }
            onNode(hasText("Pharmacology") and hasClickAction()).performClick()
            waitForIdle()
            val out = File(System.getProperty("java.io.tmpdir"), "pharmacology_dotplot.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            println("captured: ${out.absolutePath}")
        }
    }
}
