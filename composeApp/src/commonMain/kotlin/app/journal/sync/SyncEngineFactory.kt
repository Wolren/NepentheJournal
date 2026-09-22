package app.journal.sync

import app.journal.data.IJournalRepository

/**
 * Create a platform-appropriate SyncEngine implementation.
 * Desktop/android return a Ktor-based transport; iOS returns a stub.
 *
 * @param persist light persistence callback (no .bak rotation) the transport
 * runs after every accepted sync write, BEFORE the ack. The composition root
 * supplies the callback bound to its own [app.journal.data.JournalStore] so
 * exactly one store instance owns persistence (audit C5). When null (tests,
 * direct construction), the platform actual falls back to building its own
 * store from [repo].
 */
expect fun createSyncEngine(repo: IJournalRepository, persist: (() -> Unit)? = null): SyncEngine
