package app.journal.ui

import androidx.compose.runtime.compositionLocalOf
import app.journal.data.IJournalRepository

/**
 * CompositionLocal for dependency injection of the journal repository.
 * Provide at the root of the composition tree in App.kt:
 *
 *   CompositionLocalProvider(LocalRepo provides repo) {
 *       MaterialTheme { ... }
 *   }
 *
 * Screens consume via:
 *   val repo = LocalRepo.current
 */
val LocalRepo = compositionLocalOf<IJournalRepository> {
    error("No JournalRepository provided. Wrap your composable in CompositionLocalProvider(LocalRepo provides repo).")
}
