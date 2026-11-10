package app.journal.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Editing an existing session: removing a dose from the form must delete it
 * from the repository on save, and the title change must persist.
 */
@OptIn(ExperimentalTestApi::class)
class SessionEditingE2ETest {

    private val start = 1_700_000_000_000L

    private fun testSubstance() = Substance(
        id = "sub:test",
        name = "Test Substance",
        createdAt = start,
        updatedAt = start,
        deviceOrigin = "test",
        substanceClass = listOf("Psychedelic"),
        routesOfAdministration = listOf("Oral"),
        effects = emptyList(),
        dosageBands = emptyMap(),
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private fun testSession() = Session(
        id = "session:edit",
        createdAt = start,
        updatedAt = start,
        deviceOrigin = "test",
        title = "Original",
        startTime = start,
    )

    private fun testDose(id: String, amount: Double) = Dose(
        id = id,
        sessionId = "session:edit",
        substanceId = "sub:test",
        routeOfAdministration = "Oral",
        amount = amount,
        unit = "mg",
        timestamp = start,
        createdAt = start,
        updatedAt = start,
        deviceOrigin = "test",
    )

    @Test
    fun removingADoseAndRenamingPersists() {
        val repo = JournalRepository()
        val session = testSession()
        repo.upsertSession(session)
        repo.upsertDose(testDose("dose:a", 100.0))
        repo.upsertDose(testDose("dose:b", 50.0))

        val viewModel = SessionEditorViewModel(repo, session)
        viewModel.title.value = "Edited"
        viewModel.deleteDose("dose:a")
        viewModel.save()

        assertEquals("Edited", repo.getSession(session.id)?.title)
        assertEquals(listOf("dose:b"), repo.dosesForSession(session.id).map { it.id }.sorted())
    }

    @Test
    fun removingADoseFromTheEditorUiPersists() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSubstance(testSubstance())
            val session = testSession()
            repo.upsertSession(session)
            repo.upsertDose(testDose("dose:a", 100.0))
            repo.upsertDose(testDose("dose:b", 50.0))
            var closed = false

            setContent {
                MaterialTheme {
                    SessionEditorScreen(
                        repo = repo,
                        sessionToEdit = session,
                        onBack = { closed = true },
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("screen-list").performScrollToNode(hasTestTag("dose-delete-dose:a"))
            onNodeWithTag("dose-delete-dose:a").performClick()
            waitForIdle()
            onNodeWithTag("session-save").performClick()
            waitForIdle()

            assertEquals(true, closed)
            assertEquals("Original", repo.getSession(session.id)?.title)
            assertEquals(1, repo.dosesForSession(session.id).size)
        }
    }

    @Test
    fun deletingTheSessionRemovesItAndItsDoses() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSubstance(testSubstance())
            val session = testSession()
            repo.upsertSession(session)
            repo.upsertDose(testDose("dose:a", 100.0))
            var closed = false

            setContent {
                MaterialTheme {
                    SessionEditorScreen(
                        repo = repo,
                        sessionToEdit = session,
                        onBack = { closed = true },
                    )
                }
            }
            waitForIdle()

            onNodeWithContentDescription("Delete").performClick()
            waitForIdle()
            onNodeWithText("Delete").performClick()
            waitForIdle()

            assertEquals(true, closed)
            assertNull(repo.getSession(session.id))
            assertTrue(repo.dosesForSession(session.id).isEmpty())
        }
    }
}
