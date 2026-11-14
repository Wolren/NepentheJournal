package app.journal.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The tab host must keep a tab's state, and it must keep the hidden tab out of
 * touch and accessibility reach, or a phone gets an invisible scroller under
 * the visible tab.
 */
@OptIn(ExperimentalTestApi::class)
class KeepAliveTabsTest {

    @Composable
    private fun CounterTab() {
        var count by remember { mutableStateOf(0) }
        Button(onClick = { count++ }) { Text("bump") }
        Text("alpha $count")
    }

    @Test
    fun stateSurvivesASwitchAndTheHiddenTabTakesNoInput() {
        var selected by mutableStateOf(0)
        runComposeUiTest {
            setContent {
                KeepAliveTabs(selectedIndex = selected, tabCount = 2) { index ->
                    if (index == 0) CounterTab() else Text("beta")
                }
            }
            onNodeWithText("bump").performClick()
            onNodeWithText("alpha 1").assertExists()

            selected = 1
            waitForIdle()
            onNodeWithText("beta").assertExists()
            onAllNodesWithText("alpha 1").assertCountEquals(0)

            selected = 0
            waitForIdle()
            onNodeWithText("alpha 1").assertExists()
        }
    }

    @Test
    fun aTouchNeverReachesTheHiddenTab() {
        var selected by mutableStateOf(0)
        runComposeUiTest {
            setContent {
                KeepAliveTabs(selectedIndex = selected, tabCount = 2) { index ->
                    if (index == 0) CounterTab() else Text("beta")
                }
            }
            onNodeWithText("bump").performClick()
            onNodeWithText("alpha 1").assertExists()

            selected = 1
            waitForIdle()
            onRoot().performTouchInput { click(Offset(20f, 20f)) }
            waitForIdle()

            selected = 0
            waitForIdle()
            onNodeWithText("alpha 1").assertExists()
        }
    }

    @Composable
    private fun ScrollTab() {
        val state = rememberLazyListState()
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items((0 until 100).toList()) { index -> Text("Row $index") }
        }
    }

    @Test
    fun scrollPositionSurvivesASwitch() {
        var selected by mutableStateOf(0)
        runComposeUiTest {
            setContent {
                KeepAliveTabs(selectedIndex = selected, tabCount = 2) { index ->
                    if (index == 0) ScrollTab() else Text("beta")
                }
            }
            onNodeWithText("Row 0").assertExists()
            onNode(hasScrollToIndexAction()).performScrollToIndex(40)
            waitForIdle()
            onNodeWithText("Row 40").assertExists()

            selected = 1
            waitForIdle()
            onNodeWithText("beta").assertExists()

            selected = 0
            waitForIdle()
            onNodeWithText("Row 40").assertExists()
            onAllNodesWithText("Row 0").assertCountEquals(0)
        }
    }
}