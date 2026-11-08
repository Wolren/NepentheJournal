package app.journal.ui.session

import app.journal.model.CheckIn
import app.journal.model.Session
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest

/**
 * The effect timeline's time axis (commit b1047d7): labels sit BELOW the plot,
 * once, in hour units, counting from 0h. The old rendering carried a second
 * TickLabelRow above the plot and started the scale at the first step.
 *
 * A 5h window gets a 1h step (axisStepMs), so the expected labels are exactly
 * "0h" .. "4h" (the loop stops before offset == spanMs).
 */
@OptIn(ExperimentalTestApi::class)
class SessionEffectTimelineAxisTest {

    @Test
    fun axisLabelsRenderOnceBelowThePlotInHoursFromZero() {
        val start = 1_700_000_000_000L
        val session = Session(
            id = "s:axis",
            title = "Axis",
            startTime = start,
            endTime = start + 5 * 3_600_000L,
            createdAt = start,
            updatedAt = start,
            deviceOrigin = "test",
            checkins = listOf(
                CheckIn(timestamp = start + 3_600_000L, overallIntensity = 4f),
                CheckIn(timestamp = start + 4 * 3_600_000L, overallIntensity = 7f),
            ),
        )

        var density = 1f
        runComposeUiTest {
            setContent {
                density = LocalDensity.current.density
                SessionEffectTimeline(
                    session = session,
                    doses = emptyList(),
                    events = emptyList(),
                    substancesById = emptyMap(),
                    accent = Color.Blue,
                )
            }
            waitForIdle()

            fun count(text: String) = onAllNodesWithText(text)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size

            // The window follows the check-ins (1h to 4h), so the axis is stepped in minutes.
            // A duplicated label row would show as a count of 2.
            for (expected in listOf("0m", "30m", "1h", "1h30m", "2h", "2h30m", "3h")) {
                assertEquals(
                    1,
                    count(expected),
                    "axis label \"$expected\" must appear exactly once (below the plot)",
                )
            }

            // The 60dp threshold sits between the two.
            val header = onAllNodesWithText("Effect timeline")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).first()
            val firstLabel = onAllNodesWithText("0m")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).first()
            val gapPx = firstLabel.boundsInRoot.top - header.boundsInRoot.bottom
            assertTrue(
                gapPx > 60f * density,
                "the first label must sit below the 100dp plot, gap was ${gapPx}px",
            )
        }
    }
}
