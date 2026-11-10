package app.journal.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Live session lifecycle: the Stop action must write the end time and leave
 * the screen, so the session drops out of the live list.
 */
@OptIn(ExperimentalTestApi::class)
class LiveSessionE2ETest {

    @Test
    fun stoppingALiveSessionSetsTheEndTime() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            val start = 1_700_000_000_000L
            val session = Session(
                id = "session:live",
                createdAt = start,
                updatedAt = start,
                deviceOrigin = "test",
                title = "Live Test",
                startTime = start,
            )
            repo.upsertSession(session)
            var closed = false

            setContent {
                MaterialTheme {
                    LiveSessionScreen(
                        repo = repo,
                        session = session,
                        onBack = { closed = true },
                    )
                }
            }
            waitForIdle()

            onNodeWithText("Stop").performClick()
            waitForIdle()
            onNodeWithText("End").performClick()
            waitForIdle()

            assertTrue(closed)
            val ended = repo.getSession(session.id)
            assertNotNull(ended)
            assertNotNull(ended.endTime, "stopping the session must set the end time")
        }
    }
}
