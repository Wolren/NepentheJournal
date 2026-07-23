package app.journal.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-appropriate scrollbar for a [LazyListState].
 * On desktop this renders a themed scrollbar. On mobile it's a no-op.
 */
@Composable
expect fun DesktopScrollbar(
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
)
