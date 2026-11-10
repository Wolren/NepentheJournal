package app.journal.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.Substance
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Search from the user side: the first query pays the index rebuild, which
 * now runs off the frame thread, and the session must still appear.
 */
@OptIn(ExperimentalTestApi::class)
class SearchE2ETest {

    private fun testSubstance() = Substance(
        id = "sub:find",
        name = "Findable Substance",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test",
        substanceClass = listOf("Psychedelic"),
        routesOfAdministration = listOf("Oral"),
        effects = emptyList(),
        dosageBands = emptyMap(),
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private fun testSession() = Session(
        id = "session:find",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test",
        title = "Findable Session",
        startTime = 1_700_000_000_000L,
    )

    @Test
    fun firstSearchFindsASession() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSubstance(testSubstance())
            repo.upsertSession(testSession())

            setContent {
                MaterialTheme {
                    SearchOverlay(
                        repo = repo,
                        onBack = {},
                        onSessionClick = {},
                        onSubstanceClick = {},
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("search-field").performTextInput("Findable")
            waitUntil(timeoutMillis = 3_000) {
                onAllNodesWithText("Findable Session").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Findable Session").assertExists()
        }
    }
}
