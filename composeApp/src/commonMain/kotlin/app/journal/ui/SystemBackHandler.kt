package app.journal.ui

import androidx.compose.runtime.Composable

/**
 * Intercept the system back button/gesture.
 * On Android this hooks into androidx.activity.compose.BackHandler.
 * On desktop the Escape key handler in ScreenScaffold handles it.
 */
@Composable
expect fun SystemBackHandler(onBack: () -> Unit)
