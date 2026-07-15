package app.journal.ui

import androidx.compose.runtime.Composable

/**
 * iOS: the system back gesture/button is handled by UIKit's navigation
 * controller. Compose Multiplatform on iOS uses a UIViewController host,
 * so the system swipe-back gesture is already available.
 *
 * Custom back handling would require UIKit interop — for now this is a
 * no-op (the Escape/ScreenScaffold pattern isn't applicable on iOS).
 */
@Composable
actual fun SystemBackHandler(onBack: () -> Unit) {
    // iOS handles system back via UIKit. No-op here.
    // If needed in the future, use UINavigationController delegate or
    // UIBarButtonItem custom back action.
}
