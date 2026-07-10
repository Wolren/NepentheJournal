package app.journal.sync

import app.journal.data.JournalRepository

/**
 * Create a platform-appropriate SyncEngine implementation.
 * Desktop/android return a Ktor-based transport; iOS returns a stub.
 */
expect fun createSyncEngine(repo: JournalRepository): SyncEngine
