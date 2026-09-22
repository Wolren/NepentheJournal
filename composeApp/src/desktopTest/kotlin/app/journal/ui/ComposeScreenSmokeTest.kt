package app.journal.ui

import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.sync.SyncTransport
import app.journal.ui.session.SessionListScreen
import app.journal.ui.session.SessionListViewModel
import app.journal.ui.settings.SettingsScreen
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest

/**
 * Compose UI smoke tests (stretch, audit 4 item 9: "no compose ui-test infra
 * in build.gradle.kts"). They render the two largest screens with their real
 * view models and assert key structure, nothing more: no screenshot, no
 * interaction coverage is claimed here.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ComposeScreenSmokeTest {

    private lateinit var dataDir: File

    @BeforeTest
    fun setUp() {
        dataDir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-ui-smoke-${System.nanoTime()}"
        ).apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    @Test
    fun sessionListScreenRendersSearchCountAndEmptyState() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            setContent {
                SessionListScreen(
                    repo = repo,
                    viewModel = SessionListViewModel(repo)
                )
            }
            waitForIdle()

            // Search bar structure: substance filter icon lives in its trailing slot.
            assertTrue(
                onAllNodesWithContentDescription("Filter by substance")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SessionListScreen must render the substance filter action"
            )
            // Count row: an empty repository reports "0 sessions".
            assertTrue(
                onAllNodesWithText("0 sessions").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SessionListScreen must render the session count row"
            )
            // Empty state + primary action.
            assertTrue(
                onAllNodesWithText("No sessions yet").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SessionListScreen must render the empty state"
            )
            assertTrue(
                onAllNodesWithContentDescription("New Session").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SessionListScreen must render the new-session action"
            )
        }
    }

    @Test
    fun sessionListScreenBindsRepositoryDataToTheCountRow() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSession(
                Session(
                    id = "s:ui-smoke", title = "UI Smoke",
                    startTime = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    deviceOrigin = "test"
                )
            )
            setContent {
                SessionListScreen(
                    repo = repo,
                    viewModel = SessionListViewModel(repo)
                )
            }
            waitForIdle()

            assertTrue(
                onAllNodesWithText("1 session").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the count row must follow the repository (expected \"1 session\")"
            )
            assertTrue(
                onAllNodesWithText("UI Smoke").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the session title must be rendered from the repository"
            )
        }
    }

    @Test
    fun settingsScreenRendersTheThemeSection() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            val engine = SyncTransport(repo, dataDir.absolutePath)
            setContent {
                SettingsScreen(repo = repo, syncEngine = engine)
            }
            waitForIdle()

            // First LazyColumn item is ThemeContent; its header is always
            // composed, so it is a stable structure anchor.
            assertTrue(
                onAllNodesWithText("Theme").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SettingsScreen must render the Theme card"
            )
            // The theme BODY (base selector, presets, colors) sits behind
            // the collapsed "Manage" expander, so only the card header is
            // guaranteed without interaction. Matcher-based onNode/onAll
            // are not reachable from this test API version, so structure is
            // pinned through the header action below instead.
            assertTrue(
                onAllNodesWithText("Manage").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SettingsScreen must render the theme manage action"
            )
        }
    }
}
