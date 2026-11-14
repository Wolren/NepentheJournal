package app.journal.ui

import app.journal.data.JournalRepository
import app.journal.model.Medication
import app.journal.model.Substance
import app.journal.model.MedicationPreset
import app.journal.model.Session
import app.journal.sync.SyncTransport
import app.journal.ui.components.EMPTY_STATE_DELAY_MS
import app.journal.ui.session.SessionListScreen
import app.journal.ui.session.SessionListViewModel
import app.journal.ui.settings.SettingsScreen
import app.journal.ui.settings.detail.MedicationsSettingsContent
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.substances.SubstanceScreenViewModel
import androidx.compose.material3.MaterialTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

            mainClock.advanceTimeBy(EMPTY_STATE_DELAY_MS + 100)
            waitForIdle()

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
    fun substanceScreenSwitchesToTheBundledEffectLibrary() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            repo.upsertSubstance(
                Substance(
                    id = "sub:lsd", name = "LSD", substanceClass = listOf("Psychedelic"),
                    aliases = emptyList(), routesOfAdministration = emptyList(),
                    effects = emptyList(), toxicity = emptyList(),
                    createdAt = 0L, updatedAt = 0L, cachedAt = 0L, sourceVersion = "test"
                )
            )
            setContent {
                SubstanceScreen(repo = repo, viewModel = SubstanceScreenViewModel(repo))
            }
            waitForIdle()

            onNodeWithText("Effects").performClick()
            waitForIdle()

            assertTrue(
                onAllNodesWithText("Search effects...").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the search field must switch to the effects placeholder"
            )
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Anxiety")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
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

            assertTrue(
                onAllNodesWithText("Theme").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SettingsScreen must render the Theme card"
            )
            // Matcher-based onNode/onAll aren't reachable from this test API version, so structure is pinned through the header action below instead.
            assertTrue(
                onAllNodesWithText("Manage").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "SettingsScreen must render the theme manage action"
            )
        }
    }

    @Test
    fun medicationCardRendersFromTheRepository() = runBlocking {
        runComposeUiTest {
            val stamp = 1_700_000_000_000L
            val repo = JournalRepository()
            repo.upsertMedication(
                Medication(
                    id = "medication:1",
                    createdAt = stamp,
                    updatedAt = stamp,
                    deviceOrigin = "test",
                    name = "Sertraline",
                    presetId = MedicationPreset.SSRI.id,
                    startedAt = stamp,
                )
            )
            setContent {
                MaterialTheme {
                    MedicationsSettingsContent(repo)
                }
            }
            waitForIdle()

            assertTrue(
                onAllNodesWithText("Medications").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the medication card must render"
            )
            assertTrue(
                onAllNodesWithText("1 active").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the card count must follow the repository"
            )
        }
    }
}
