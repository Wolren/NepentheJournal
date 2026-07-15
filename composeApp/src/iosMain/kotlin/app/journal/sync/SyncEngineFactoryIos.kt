package app.journal.sync

import app.journal.data.JournalRepository

/**
 * iOS sync engine factory — returns an IosSyncTransport that uses
 * plain HTTP + HMAC (no TLS) with Ktor Darwin for client and CIO for server.
 */
actual fun createSyncEngine(repo: JournalRepository): SyncEngine =
    IosSyncTransport(repo)
