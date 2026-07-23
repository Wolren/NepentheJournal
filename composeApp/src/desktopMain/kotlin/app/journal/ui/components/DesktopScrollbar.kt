package app.journal.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

@Composable
actual fun DesktopScrollbar(
    scrollState: LazyListState,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState),
            style = ScrollbarStyle(
                minimalHeight = 24.dp,
                thickness = 6.dp,
                shape = RoundedCornerShape(3.dp),
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        )
    }
}
