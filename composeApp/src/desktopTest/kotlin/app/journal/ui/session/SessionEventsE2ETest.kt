package app.journal.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Draft events are persisted under a provisional session id. Saving a new
 * session must reparent them; discarding must remove them.
 */
@OptIn(ExperimentalTestApi::class)
class SessionEventsE2ETest {

    @Test
    fun eventAddedToANewSessionIsReparentedOnSave() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            var closed = false
            setContent {
                MaterialTheme {
                    SessionEditorScreen(repo = repo, onBack = { closed = true })
                }
            }
            waitForIdle()

            onNodeWithTag("session-title").performTextInput("Event session")
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("session-add-event"))
            onNodeWithTag("session-add-event").performClick()
            waitForIdle()
            onNodeWithTag("session-save").performClick()
            waitForIdle()

            assertTrue(closed)
            val session = repo.sessions.value.single()
            val events = repo.timelineEvents.value
            assertEquals(1, events.size)
            assertEquals(session.id, events.single().sessionId)
        }
    }

    @Test
    fun editingAnInlineEventSurvivesTheSessionSave() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            var closed = false
            setContent {
                MaterialTheme {
                    SessionEditorScreen(repo = repo, onBack = { closed = true })
                }
            }
            waitForIdle()

            onNodeWithTag("session-title").performTextInput("Event edit")
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("session-add-event"))
            onNodeWithTag("session-add-event").performClick()
            waitForIdle()
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("event-label"))
            onNodeWithTag("event-label").performTextClearance()
            onNodeWithTag("event-label").performTextInput("Edited label")
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("event-save"))
            onNodeWithTag("event-save").performClick()
            waitForIdle()
            onNodeWithTag("session-save").performClick()
            waitForIdle()

            assertTrue(closed)
            assertEquals("Edited label", repo.timelineEvents.value.single().label)
        }
    }

    @Test
    fun discardingANewSessionRemovesItsDraftEvent() = runBlocking {        runComposeUiTest {
            val repo = JournalRepository()
            var closed = false
            setContent {
                MaterialTheme {
                    SessionEditorScreen(repo = repo, onBack = { closed = true })
                }
            }
            waitForIdle()

            onNodeWithTag("session-title").performTextInput("Draft session")
            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("session-add-event"))
            onNodeWithTag("session-add-event").performClick()
            waitForIdle()
            onNodeWithContentDescription("Back").performClick()
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithText("Discard").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Discard").performClick()
            waitForIdle()

            assertTrue(closed)
            assertTrue(repo.timelineEvents.value.isEmpty(), "the draft event must be purged")
            assertTrue(repo.sessions.value.isEmpty())
        }
    }
}
