package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.data.JournalStore

/**
 * Production wiring: every sync write is persisted to the journal file
 * BEFORE it is acknowledged (audit D1), using a light save without .bak
 * rotation (the debounced autosave does the full backup). The composition
 * root passes its own store's light save as [persist]; the fallback store
 * below only runs when no caller supplies one (tests construct SyncTransport
 * directly without any callback so they never touch the real app data dir).
 */
actual fun createSyncEngine(repo: IJournalRepository, persist: (() -> Unit)?): SyncEngine =
    SyncTransport(repo, persistAfterApply = persist ?: { JournalStore(repo).save(fullBackup = false) })
