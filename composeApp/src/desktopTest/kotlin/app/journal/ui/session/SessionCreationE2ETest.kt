package app.journal.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Substance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Session creation from the user side: the dose dialog builds a valid dose,
 * rejects invalid amounts, and the editor save persists the session and its
 * dose. The end-time guard is checked at the view-model level because the
 * time field is a text editor, not a click target.
 */
@OptIn(ExperimentalTestApi::class)
class SessionCreationE2ETest {

    private fun testSubstance() = Substance(
        id = "sub:test",
        name = "Test Substance",
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

    private fun dose(amount: Double = 100.0) = Dose(
        id = "dose:e2e",
        sessionId = "",
        substanceId = "sub:test",
        routeOfAdministration = "Oral",
        amount = amount,
        unit = "mg",
        timestamp = 1_700_000_000_000L,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test",
    )

    @Test
    fun doseDialogBuildsADose() = runBlocking {
        runComposeUiTest {
            var saved: Dose? = null
            setContent {
                MaterialTheme {
                    DoseEditDialog(
                        substances = listOf(testSubstance()),
                        sessionStartTime = 1_700_000_000_000L,
                        initialDose = null,
                        onDismiss = {},
                        onSave = { saved = it },
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("dose-substance").performTextInput("Test")
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithText("Test Substance").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Test Substance").performClick()
            waitForIdle()
            onNodeWithTag("dose-amount").performTextInput("100")
            onNodeWithTag("dose-save").performClick()
            waitForIdle()

            val dose = assertNotNull(saved, "the dialog must emit a dose")
            assertEquals(100.0, dose.amount)
            assertEquals("sub:test", dose.substanceId)
        }
    }

    @Test
    fun doseDialogRejectsNegativeAmount() = runBlocking {
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    DoseEditDialog(
                        substances = listOf(testSubstance()),
                        sessionStartTime = 1_700_000_000_000L,
                        initialDose = null,
                        onDismiss = {},
                        onSave = {},
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("dose-substance").performTextInput("Test")
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithText("Test Substance").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Test Substance").performClick()
            waitForIdle()
            onNodeWithTag("dose-amount").performTextInput("-5")
            onNodeWithTag("dose-save").assertIsNotEnabled()
        }
    }

    @Test
    fun editorCreatesSessionWithDoseFromTheUi() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSubstance(testSubstance())
            var closed = false
            setContent {
                MaterialTheme {
                    SessionEditorScreen(repo = repo, onBack = { closed = true })
                }
            }
            waitForIdle()

            onNodeWithTag("session-title").performTextInput("UI Session")
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("session-add-dose"))
            onNodeWithTag("session-add-dose").performClick()
            waitForIdle()
            onNodeWithTag("dose-substance").performTextInput("Test")
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithText("Test Substance").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Test Substance").performClick()
            waitForIdle()
            onNodeWithTag("dose-amount").performTextInput("100")
            onNodeWithTag("dose-save").performClick()
            waitForIdle()
            onNodeWithTag("session-save").performClick()
            waitForIdle()

            assertTrue(closed, "save must close the editor")
            val session = repo.sessions.value.single()
            assertEquals("UI Session", session.title)
            assertEquals(1, repo.dosesForSession(session.id).size)
        }
    }

    @Test
    fun savePersistsTheTitleAndTheDose() {
        val repo = JournalRepository()
        val viewModel = SessionEditorViewModel(repo, null)
        viewModel.title.value = "E2E Session"
        viewModel.upsertDose(dose())
        viewModel.save()

        val session = repo.sessions.value.single()
        assertEquals("E2E Session", session.title)
        val persisted = repo.dosesForSession(session.id)
        assertEquals(1, persisted.size)
        assertEquals(100.0, persisted.single().amount)
        assertEquals("sub:test", persisted.single().substanceId)
    }

    @Test
    fun sessionThatEndsBeforeItStartsIsNotSaved() {
        val repo = JournalRepository()
        val viewModel = SessionEditorViewModel(repo, null)
        viewModel.title.value = "Bad time"
        viewModel.startTime.value = 1_700_000_000_000L
        viewModel.endTime.value = 1_699_000_000_000L

        viewModel.save()

        assertTrue(repo.sessions.value.isEmpty(), "an end before the start must not persist")
    }
}
