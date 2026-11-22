package app.journal.ui.substances.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Substance
import kotlin.test.Test

/**
 * Interaction rows decide whether a partner can be opened from the substance
 * map. A one-shot remembered read kept the link dead when the library finished
 * loading after the first frame.
 */
@OptIn(ExperimentalTestApi::class)
class InteractionsLinkFreshnessTest {

    private fun substance(id: String, name: String) = Substance(
        id = id, name = name, createdAt = 0L, updatedAt = 0L,
        deviceOrigin = "test", cachedAt = 0L, sourceVersion = "test",
    )

    private fun interaction(otherId: String) = Interaction(
        id = "i:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        substanceAId = "sub:main", substanceBId = otherId,
        riskLevel = InteractionRisk.LOW,
    )

    @Test
    fun aRowBecomesClickableWhenTheSubstanceArrives() = runComposeUiTest {
        var substances by mutableStateOf(emptyMap<String, Substance>())

        setContent {
            MaterialTheme {
                InteractionsSection(
                    substancesById = substances,
                    interactions = listOf(interaction("sub:other")),
                    substanceId = "sub:main",
                )
            }
        }

        onNodeWithText("sub:other").assertExists()

        substances = mapOf("sub:other" to substance("sub:other", "Other substance"))
        waitForIdle()

        onNodeWithText("Other substance").assertExists()
        onNodeWithText("Other substance").assertHasClickAction()
    }
}
