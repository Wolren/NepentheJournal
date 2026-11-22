package app.journal.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import kotlin.test.Test

/**
 * A single character is below the search minimum. The screen carries a hint
 * for exactly that state, so the hint must be reachable.
 */
@OptIn(ExperimentalTestApi::class)
class SearchOverlayHintTest {

    @Test
    fun oneCharacterShowsTheMinimumLengthHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SearchOverlay(
                    repo = JournalRepository(),
                    onBack = {},
                    onSessionClick = {},
                    onSubstanceClick = {},
                )
            }
        }

        onNodeWithTag("search-field").performTextInput("a")
        waitForIdle()

        onNodeWithText("Type at least 2 characters to search").assertExists()
    }
}
