package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import app.journal.data.JournalRepository
import app.journal.ui.settings.DataSettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Data card once carried two buttons labelled "Import", and a label wider
 * than its button clipped and pushed the icon off centre.
 */
@OptIn(ExperimentalTestApi::class)
class DataSettingsCardTest {

    @Test
    fun theBackupRowsCarryDistinctLabels() = runComposeUiTest {
        val repo = JournalRepository()
        setContent {
            MaterialTheme {
                Column {
                    DataSettingsContent(
                        vm = DataSettingsViewModel(repo),
                        sessionCount = 0,
                        substanceCount = 0,
                        dataExpanded = true,
                        onDataExpanded = {},
                    )
                }
            }
        }
        waitForIdle()

        fun count(label: String) =
            onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).size

        // Only the session row imports; the full-backup row restores instead.
        assertEquals(1, count("Import"), "exactly one button may read Import")
        assertEquals(1, count("Backup"), "the full-backup row needs its own Backup button")
        assertEquals(1, count("Restore"), "the full-backup row needs a Restore button")
        assertEquals(1, count("Seal backup"), "the encrypted row needs its own seal button")
        assertEquals(1, count("Restore sealed"), "the encrypted row needs its own restore button")
        assertTrue(count("Automatic backup") == 1, "the .auto backup section needs a distinct heading")
    }
}
