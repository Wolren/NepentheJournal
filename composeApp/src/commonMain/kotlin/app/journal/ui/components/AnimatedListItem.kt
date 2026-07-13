package app.journal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Non-animated alternative to AnimatedListItem — just renders content directly.
 * Use this on mobile to avoid stutter from AnimatedVisibility in lists.
 */
@Composable
fun AnimatedListItem(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    content()
}
