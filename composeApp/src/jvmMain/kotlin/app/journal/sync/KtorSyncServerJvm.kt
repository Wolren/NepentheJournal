package app.journal.sync

// Wave2 file split (file-size-governor, 800-line limit): every declaration
// formerly in this file moved VERBATIM to focused files in sync/server/:
//   KtorSyncServer.kt (engine/bootstrap), SyncServerRouting.kt (route table),
//   SyncServerSecurity.kt (rate limits, content-length, auth verification),
//   SyncServerHandlers.kt (push/pull conflict handlers).
// Package and class names are unchanged, so every import keeps resolving.
