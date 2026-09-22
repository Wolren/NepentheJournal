package app.journal.sync

import app.journal.data.IJournalRepository

/**
 * iOS sync engine factory: returns an IosSyncTransport that uses
 * plain HTTP + HMAC (no TLS) with Ktor Darwin for client and CIO for server.
 * [persist] is forwarded to the transport so the composition root's store
 * stays the single persistence owner; when null the transport falls back to
 * building its own store from [repo].
 */
actual fun createSyncEngine(repo: IJournalRepository, persist: (() -> Unit)?): SyncEngine =
    IosSyncTransport(repo, persistAfterApply = persist)
