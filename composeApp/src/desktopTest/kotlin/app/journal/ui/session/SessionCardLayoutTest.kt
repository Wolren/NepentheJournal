package app.journal.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.journal.model.Session
import app.journal.util.TimeDisplayMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A title that fills its column ended flush against the rating badge, which is
 * the report. The gap depends on the card width, so the sweep walks the widths
 * where the title switches between one and two lines.
 */
@OptIn(ExperimentalTestApi::class)
class SessionCardLayoutTest {

    private val start = 1_700_000_000_000L

    private val session = Session(
        id = "s:gap",
        createdAt = start,
        updatedAt = start,
        deviceOrigin = "test",
        title = "Sunset Zolpidem ceremony",
        startTime = start,
        endTime = start + 8 * 3_600_000L,
        rating = 10,
    )

    @Test
    fun titleKeepsClearOfTheRatingBadgeAtEveryWidth() {
        var width by mutableStateOf(300.dp)
        var smallest = Float.MAX_VALUE
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(width)) {
                        SessionCard(
                            session = session,
                            doses = emptyList(),
                            substanceNameMap = emptyMap(),
                            timeDisplayMode = TimeDisplayMode.RELATIVE,
                            onClick = {},
                            onEdit = {},
                            onToggleFavorite = {},
                        )
                    }
                }
            }
            for (candidate in 300..520 step 2) {
                width = candidate.dp
                waitForIdle()
                val title = onNode(hasText(session.title), useUnmergedTree = true).getBoundsInRoot()
                val badge = onNode(hasText("10/10"), useUnmergedTree = true).onParent().getBoundsInRoot()
                val gap = (badge.left - title.right).value
                smallest = minOf(smallest, gap)
            }
        }
        assertTrue(
            smallest >= 12f,
            "the title must clear the rating badge by 12dp at every width, the smallest gap was $smallest dp",
        )
    }
}