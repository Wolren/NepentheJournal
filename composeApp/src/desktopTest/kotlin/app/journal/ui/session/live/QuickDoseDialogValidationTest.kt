package app.journal.ui.session.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.Substance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live quick-log dialog must not accept an amount it cannot parse.
 * The full dose editor disables Save on an invalid amount; this path must agree.
 */
@OptIn(ExperimentalTestApi::class)
class QuickDoseDialogValidationTest {

    private val session = Session(
        id = "s:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
        title = "Live", startTime = 1_700_000_000_000L,
    )

    @Test
    fun anUnparseableAmountCannotBeLogged() = runComposeUiTest {
        setContent {
            MaterialTheme {
                QuickDoseDialog(
                    substances = emptyList(),
                    session = session,
                    repo = JournalRepository(),
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("quick-dose-custom").performTextInput("Test substance")
        onNodeWithTag("quick-dose-amount").performTextInput("abc")

        onNodeWithText("Log").assertIsNotEnabled()

        onNodeWithTag("quick-dose-amount").performTextClearance()
        onNodeWithTag("quick-dose-amount").performTextInput("100")
        onNodeWithText("Log").assertIsEnabled()
    }

    @Test
    fun aTypedNameThatMatchesTheLibraryDoesNotCreateADuplicate() = runComposeUiTest {
        val repo = JournalRepository()
        repo.upsertSubstance(
            Substance(
                id = "sub:lsd", name = "LSD", createdAt = 1L, updatedAt = 1L,
                deviceOrigin = "test", cachedAt = 0L, sourceVersion = "test",
            )
        )

        setContent {
            MaterialTheme {
                QuickDoseDialog(
                    substances = repo.substances.value,
                    session = session,
                    repo = repo,
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("quick-dose-custom").performTextInput("LSD")
        onNodeWithTag("quick-dose-amount").performTextInput("100")
        onNodeWithText("Log").performClick()
        waitForIdle()

        assertEquals(1, repo.substances.value.size, "typing an existing name must not create a duplicate")
        assertEquals("sub:lsd", repo.doses.value.single().substanceId)
    }
}
