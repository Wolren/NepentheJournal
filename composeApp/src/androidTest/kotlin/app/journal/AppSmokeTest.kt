package app.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke: the paths no unit test reaches, being the Activity start, the
 * first frame that must arrive before the bundled library finishes, and the
 * tabs. The library build runs on an application scope, so this waits for it.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun dashboardRendersThenSubstanceListOpens() {
        waitFor("Dashboard")
        rule.onNodeWithText("Substances").performClick()
        waitFor("Search substances")
        rule.onNodeWithText("Search substances", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsTabShowsSyncCard() {
        waitFor("Dashboard")
        rule.onNodeWithText("Settings").performClick()
        waitFor("Device Sync")
        rule.onNodeWithText("Device Sync", substring = true).assertIsDisplayed()
    }
}
