package app.journal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * A tab is rebuilt on every switch, so its scroll offset has to come back from
 * the saveable registry rather than from a live composition.
 */
@OptIn(ExperimentalTestApi::class)
class TabScrollStateTest {

    @Test
    fun scrollOffsetSurvivesASwitchOfTheTabKey() {
        var selected by mutableStateOf(0)
        runComposeUiTest {
            setContent {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(selected) {
                    if (selected == 0) {
                        val state = rememberLazyListState()
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                            items((0 until 100).toList()) { index -> Text("Row $index") }
                        }
                    } else {
                        Text("other tab")
                    }
                }
            }
            onNodeWithText("Row 0").assertExists()
            onNode(hasScrollToIndexAction()).performScrollToIndex(40)
            waitForIdle()
            onNodeWithText("Row 40").assertExists()

            selected = 1
            waitForIdle()
            onNodeWithText("other tab").assertExists()

            selected = 0
            waitForIdle()
            onNodeWithText("Row 40").assertExists()
        }
    }
}