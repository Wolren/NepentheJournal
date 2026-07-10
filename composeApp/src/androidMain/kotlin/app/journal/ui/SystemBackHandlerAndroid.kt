package app.journal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}
