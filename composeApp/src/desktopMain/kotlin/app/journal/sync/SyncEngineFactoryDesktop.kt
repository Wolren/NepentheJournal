package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.data.JournalStore

/**
 * Production wiring: every sync write (server push/WS delta, client pull/WS
 * delta) is persisted to the journal file BEFORE it is acknowledged, so a
 * crash cannot lose data the peer believes was accepted (audit D1).
 * Uses a light save (no .bak rotation; that would copy the journal 6 times
 * per sync frame); the debounced autosave performs the full backup shortly
 * after. Tests construct SyncTransport directly without the callback so they
 * never touch the real user home data path.
 */
actual fun createSyncEngine(repo: JournalRepository): SyncEngine =
    SyncTransport(repo, persistAfterApply = { JournalStore(repo).save(fullBackup = false) })
