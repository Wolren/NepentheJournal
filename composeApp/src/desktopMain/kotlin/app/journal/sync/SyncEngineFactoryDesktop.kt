package app.journal.sync

import app.journal.data.JournalRepository

actual fun createSyncEngine(repo: JournalRepository): SyncEngine =
    SyncTransport(repo)
