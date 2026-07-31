package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.data.JournalStore

/**
 * Production wiring: every sync write is persisted to the journal file
 * BEFORE it is acknowledged (audit D1), using a light save without .bak
 * rotation (the debounced autosave does the full backup). Tests construct
 * SyncTransport directly without the callback so they never touch the real
 * app data dir.
 */
actual fun createSyncEngine(repo: JournalRepository): SyncEngine =
    SyncTransport(repo, persistAfterApply = { JournalStore(repo).save(fullBackup = false) })
