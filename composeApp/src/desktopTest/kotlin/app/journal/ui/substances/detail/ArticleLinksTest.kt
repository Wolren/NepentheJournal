package app.journal.ui.substances.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import app.journal.model.Substance
import app.journal.model.WikipediaData
import kotlin.test.Test

/**
 * A link button exists only when its record exists. Each case pins one gate,
 * so a missing article never shows a button that leads nowhere.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleLinksTest {

    private fun substance(
        pwikiId: String? = null,
        dosewikiSlug: String? = null,
        wikipedia: Boolean = false,
        cid: Long? = null,
        drugbankId: String? = null,
    ) = Substance(
        id = "cid:${cid ?: 1}",
        cid = cid,
        createdAt = 0L,
        updatedAt = 0L,
        pwikiId = pwikiId,
        dosewikiSlug = dosewikiSlug,
        name = "Test compound",
        wikipediaData = if (wikipedia) WikipediaData() else null,
        drugbankId = drugbankId,
        cachedAt = 0L,
        sourceVersion = "test",
    )

    @Test
    fun everyRecordGetsItsButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ArticleLinkIcons(
                    substance(
                        pwikiId = "LSD",
                        dosewikiSlug = "lsd",
                        wikipedia = true,
                        cid = 5761,
                        drugbankId = "DB04829",
                    ),
                )
            }
        }
        listOf(
            "Open DoseWiki article",
            "Open PsychonautWiki article",
            "Open Wikipedia article",
            "Open PubChem entry",
            "Open DrugBank entry",
        ).forEach { onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    fun noRecordMeansNoButton() = runComposeUiTest {
        setContent {
            MaterialTheme { ArticleLinkIcons(substance()) }
        }
        listOf(
            "Open DoseWiki article",
            "Open PsychonautWiki article",
            "Open Wikipedia article",
            "Open PubChem entry",
            "Open DrugBank entry",
        ).forEach { onNodeWithContentDescription(it).assertDoesNotExist() }
    }

    @Test
    fun onlyPubChemWhenOnlyCidExists() = runComposeUiTest {
        setContent {
            MaterialTheme { ArticleLinkIcons(substance(cid = 5761)) }
        }
        onNodeWithContentDescription("Open PubChem entry").assertExists()
        onNodeWithContentDescription("Open PsychonautWiki article").assertDoesNotExist()
        onNodeWithContentDescription("Open Wikipedia article").assertDoesNotExist()
    }
}
