package app.journal.ui.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.ui.LocalJournalRepository
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DashboardEmptyStateTest {

    @Test
    fun populatedJournalWithoutToleranceCardDoesNotShowEmptyState() = runComposeUiTest {
        val repo = JournalRepository()
        repo.upsertSession(
            Session(
                id = "s:1",
                createdAt = 0L,
                updatedAt = 0L,
                deviceOrigin = "test",
                title = "Session",
                startTime = 1000L
            )
        )
        repo.setDashboardTolerance(false)

        setContent {
            CompositionLocalProvider(LocalJournalRepository provides repo) {
                DashboardScreen(repo = repo)
            }
        }

        onNodeWithText("No sessions data yet").assertDoesNotExist()
    }
}
