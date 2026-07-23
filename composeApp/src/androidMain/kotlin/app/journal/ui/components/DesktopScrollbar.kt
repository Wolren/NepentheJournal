package app.journal.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun DesktopScrollbar(
    scrollState: LazyListState,
    modifier: Modifier,
) {
    // No-op on Android — scrollbar is Desktop-only
}
