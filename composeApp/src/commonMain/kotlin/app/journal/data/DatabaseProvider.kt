package app.journal.data

/**
 * expect/actual: platform-specific Couchbase Lite initialization.
 *
 * Collections (all in _default scope):
 *   sessions, doses, timelineEvents, notes,
 *   substances, effects, interactions,
 *   links, persons, attachments, devices, syncConfigs
 *
 * Indexes to create on first open:
 *   FTS  on notes.body           → for MATCH(fts_notes, ...)
 *   Value on sessions.startTime  → for ORDER BY startTime DESC
 *   Value on doses.sessionId     → for WHERE sessionId = $id
 *
 * Platform TLS key storage:
 *   Android   → Android System KeyStore (hardware-backed, API 28+)
 *   JVM/Win   → Java PKCS12 KeyStore / CNG Key Storage Provider
 *   iOS/macOS → Keychain services
 *   Linux JVM → user-managed PKCS12 file (no OS standard)
 */
expect class DatabaseProvider(vaultName: String = "psychonautica-vault") {
    fun open()
    fun close()
}
