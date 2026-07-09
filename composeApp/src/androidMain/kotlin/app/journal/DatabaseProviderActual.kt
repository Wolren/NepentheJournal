package app.journal.data
import com.couchbase.lite.Database
import com.couchbase.lite.DatabaseConfiguration

/**
 * Android actual. CouchbaseLite.init(context) must be called once per process
 * (e.g., in Application.onCreate()). Add an Application class that calls it
 * and declare it in AndroidManifest.xml if not already done.
 *
 * ProGuard rules are in composeApp/proguard-rules.pro (from kotbase.dev docs).
 *
 * FTS + value indexes created on first open for fast queries:
 *   - fts_notes        on notes.body (for MATCH full-text search)
 *   - idx_sessions_ts  on sessions.startTime (for ORDER BY startTime DESC)
 *   - idx_doses_sess   on doses.sessionId (for WHERE sessionId = $id)
 */
actual class DatabaseProvider actual constructor(private val vaultName: String) {
    private var db: Database? = null
    actual fun open() {
        db = Database(vaultName, DatabaseConfiguration())
        setupCollections()
    }
    actual fun close() { db?.close(); db = null }
    private fun setupCollections() {
        val d = db ?: return
        val scope = "_default"
        listOf(
            "sessions","doses","timelineEvents","notes",
            "substances","effects","interactions",
            "links","persons","attachments","devices","syncConfigs"
        ).forEach { d.createCollection(it, scope) }
    }
}
