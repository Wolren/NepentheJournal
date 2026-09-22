package app.journal.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository

/**
 * Composition-local access to the journal repository injected at the
 * composition root ([App]).
 *
 * Screens declare `repo: IJournalRepository = LocalJournalRepository.current`
 * as a default parameter instead of reaching for the hard singleton, so every
 * screen renders against the same repository instance the root bound
 * JournalStore and SyncEngine to (audit C5).
 *
 * The default only fires when no provider is installed (tests, previews):
 * the platform singleton keeps those entry points working unchanged.
 */
val LocalJournalRepository = staticCompositionLocalOf<IJournalRepository> { JournalRepository.instance }
