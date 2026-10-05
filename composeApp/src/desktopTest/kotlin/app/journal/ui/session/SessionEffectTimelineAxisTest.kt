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

            // The axis reads in whole hours from the origin: every expected
            // label appears exactly once. A duplicated label row would show as
            // a count of 2 - the pre-b1047d7 rendering had one above and one
            // below the plot.
            for (expected in listOf("0h", "1h", "2h", "3h", "4h")) {
                assertEquals(
                    1,
                    count(expected),
                    "axis label \"$expected\" must appear exactly once (below the plot)",
                )
            }
            // axisLabel(0) is "0m"; on an hour-stepped axis it must never leak.
            assertEquals(0, count("0m"), "an hour axis must not show a \"0m\" label")

            // Geometry: the 0h label sits under the 100dp plot, not tucked
            // between the header and the plot where the removed top row was.
            // Below the plot the gap from the header's bottom to the label's
            // top is 8dp spacer + 100dp canvas + 3dp spacer = 111dp; the old
            // top row was ~22dp. The 60dp threshold sits between the two.
            val header = onAllNodesWithText("Effect timeline")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).first()
            val firstLabel = onAllNodesWithText("0h")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).first()
            val gapPx = firstLabel.boundsInRoot.top - header.boundsInRoot.bottom
            assertTrue(
                gapPx > 60f * density,
                "the 0h label must sit below the 100dp plot, gap was ${gapPx}px",
            )
        }
    }
}
