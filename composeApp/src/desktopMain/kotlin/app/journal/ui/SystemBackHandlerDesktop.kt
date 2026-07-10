package app.journal.ui

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(onBack: () -> Unit) {
    // No-op: desktop uses ScreenScaffold's Escape key handler
}
