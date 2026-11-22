package app.journal.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import kotlin.test.Test

/**
 * The export preview names doses from the substance library. Opening the
 * dialog before the library finishes loading must not block the export for
 * good; the preview has to recover when the substances arrive.
 */
@OptIn(ExperimentalTestApi::class)
class DoseWikiExportDialogFreshnessTest {

    private fun substance(id: String, name: String) = Substance(
        id = id, name = name, createdAt = 1L, updatedAt = 1L,
        deviceOrigin = "test", cachedAt = 0L, sourceVersion = "test",
    )

    @Test
    fun thePreviewRecoversWhenTheSubstanceLibraryArrives() = runComposeUiTest {
        val repo = JournalRepository()
        repo.upsertSession(
            Session(
                id = "s:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                title = "Evening walk", startTime = 1_700_000_000_000L,
            )
        )
        repo.upsertDose(
            Dose(
                id = "d:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "sub:late",
                routeOfAdministration = "Oral", amount = 10.0, unit = "mg",
                timestamp = 1_700_000_000_000L,
            )
        )

        setContent {
            MaterialTheme {
                DoseWikiExportDialog(
                    repo = repo,
                    sessionId = "s:1",
                    scope = rememberCoroutineScope(),
                    onDismiss = {},
                    onStatus = {},
                )
            }
        }

        onNodeWithText("At least one substance is required.").assertExists()

        repo.upsertSubstance(substance("sub:late", "Late substance"))
        waitForIdle()

        onNodeWithText("At least one substance is required.").assertDoesNotExist()
    }
}
