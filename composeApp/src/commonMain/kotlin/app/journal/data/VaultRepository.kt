package app.journal.data

import app.journal.model.VaultDocument
import kotlinx.coroutines.flow.Flow

/**
 * Generic repository over a Couchbase Lite collection.
 *
 * SQL++ examples (Couchbase Lite mobile dialect):
 *
 *   Sessions by tag, newest first:
 *     SELECT * FROM _ WHERE docType = "session"
 *       AND ARRAY_CONTAINS(tags, "psilocybin")
 *       ORDER BY startTime DESC
 *
 *   Doses for a session:
 *     SELECT * FROM _ WHERE docType = "dose"
 *       AND sessionId = $sessionId
 *       ORDER BY timestamp ASC
 *
 *   Full-text search on note body (requires FTS index "fts_notes"):
 *     SELECT * FROM _ WHERE docType = "note"
 *       AND MATCH(fts_notes, $term)
 *       ORDER BY RANK(fts_notes)
 *
 *   Bidirectional interaction lookup:
 *     SELECT * FROM _ WHERE docType = "interaction"
 *       AND (substanceAId = $id OR substanceBId = $id)
 */
interface VaultRepository<T : VaultDocument> {
    suspend fun upsert(document: T)
    suspend fun getById(id: String): T?
    suspend fun delete(id: String)
    fun observeAll(): Flow<List<T>>
    fun observeByQuery(
        sqlPlusPlus: String,
        params: Map<String, Any?> = emptyMap()
    ): Flow<List<T>>
}
