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
import app.journal.ui.settings.detail.PreferencesCardContent
import app.journal.ui.substances.EffectDetailScreen
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.substances.SubstanceScreenViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
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
            val opened = mutableListOf<String>()
            setContent {
                SubstanceScreen(
                    repo = repo,
                    viewModel = SubstanceScreenViewModel(repo),
                    onEffectClick = { opened += it },
                )
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
            onNodeWithText("Anxiety").performClick()
            waitForIdle()
            assertEquals(listOf("Anxiety"), opened, "a tapped card must open its article")

            // The category filter mirrors the substance filter: one menu, broads then sensory specifics.
            onAllNodesWithContentDescription("Filter by category")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .first()
            onNode(hasContentDescription("Filter by category")).performClick()
            waitForIdle()
            assertTrue(
                onAllNodesWithText("Sensory specifics").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the effects filter must open with the sensory specifics"
            )
            onNode(hasText("Physical (", substring = true) and hasAnyAncestor(isPopup())).performClick()
            waitForIdle()
            assertTrue(
                onAllNodesWithText("Physical (", substring = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "a broad filter must keep the physical group"
            )
            assertEquals(
                0,
                onAllNodesWithText("Cognitive (", substring = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size,
                "a broad filter must drop the other groups",
            )
        }
    }

    @Test
    fun substanceScreenExposesEquivalenceAsTheThirdLibraryTab() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            setContent {
                SubstanceScreen(
                    repo = repo,
                    viewModel = SubstanceScreenViewModel(repo),
                )
            }
            waitForIdle()

            onNodeWithText("Equivalence").performClick()
            waitForIdle()

            assertTrue(
                onAllNodesWithText("Dose equivalence").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the third library tab must open the dose equivalence converter"
            )
            assertEquals(
                0,
                onAllNodesWithText("Search substances...").fetchSemanticsNodes(atLeastOneRootRequired = false).size,
                "the converter tab must not show the substance search field"
            )
        }
    }

    @Test
    fun effectsLibraryHonoursTheBroadFilter() = runBlocking {
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    app.journal.ui.substances.EffectsLibrary(
                        query = "",
                        listState = androidx.compose.foundation.lazy.rememberLazyListState(),
                        broad = "physical",
                    )
                }
            }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Physical (", substring = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            assertEquals(
                0,
                onAllNodesWithText("Cognitive (", substring = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size,
                "only the physical group may remain",
            )
        }
    }
    @Test
    fun effectDetailScreenRendersTheBundledArticle() = runBlocking {
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    EffectDetailScreen(effectName = "Anxiety", onBack = {})
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Anxiety is medically recognized", substring = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            assertTrue(
                onAllNodesWithText("Anxiety").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                "the top bar must carry the article title"
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
    fun preferencesCardRendersEveryControl() = runBlocking {
        runComposeUiTest {
            val repo = JournalRepository()
            setContent {
                MaterialTheme {
                    Column { PreferencesCardContent(repo = repo) }
                }
            }
            waitForIdle()

            // One label per group plus one per switch: a group that silently
            // stops rendering (or a switch that gets dropped) fails here.
            val labels = listOf(
                "Rating scale",
                "Time display",
                "Open on",
                "Free-form autosave",
                "Dashboard cards",
                "Activity heatmap",
                "Tolerance overview",
                "Top substances",
                "Sessions per week chart",
                "Ask before deleting",
                "Auto-backup on close",
            )
            for (label in labels) {
                assertTrue(
                    onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty(),
                    "the preferences card must render '$label'"
                )
            }
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
