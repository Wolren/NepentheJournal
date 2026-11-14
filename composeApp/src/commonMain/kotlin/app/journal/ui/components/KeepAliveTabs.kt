package app.journal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Places only the pane at [selectedIndex]. Every pane stays composed and
 * measured, so a tab keeps its state and scroll offset and skips its entry
 * work when the user comes back. Hidden panes are not placed, so they take no
 * touches and draw nothing, and their semantics are cleared so a screen reader
 * cannot reach a tab the user cannot see.
 *
 * The host sizes itself to the parent, so it needs bounded constraints.
 */
@Composable
fun KeepAliveTabs(
    selectedIndex: Int,
    tabCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    Layout(
        content = {
            for (index in 0 until tabCount) {
                key(index) {
                    val hidden = index != selectedIndex
                    Box(
                        Modifier.fillMaxSize().then(
                            if (hidden) Modifier.clearAndSetSemantics { } else Modifier
                        )
                    ) {
                        content(index)
                    }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else placeables.maxOfOrNull { it.width } ?: 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight
        else placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.getOrNull(selectedIndex)?.place(0, 0)
        }
    }
}