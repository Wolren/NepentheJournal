package app.journal.data
import app.journal.log.Log
import com.couchbase.lite.Database
import com.couchbase.lite.DatabaseConfiguration

/**
 * Android actual. CouchbaseLite.init(context) must be called once per process
 * (e.g., in Application.onCreate()). Add an Application class that calls it
 * and declare it in AndroidManifest.xml if not already done.
 */
actual class DatabaseProvider actual constructor(private val vaultName: String) {
    private var db: Database? = null
    actual fun open() {
        try {
            db = Database(vaultName, DatabaseConfiguration())
            setupCollections()
            Log.withTag("DB").i { "Opened Couchbase vault: $vaultName" }
        } catch (e: Exception) {
            Log.withTag("DB").e(e) { "Failed to open Couchbase vault: $vaultName" }
            throw e
        }
    }
    actual fun close() {
        try {
            db?.close()
            Log.withTag("DB").i { "Closed Couchbase vault: $vaultName" }
        } catch (e: Exception) {
            Log.withTag("DB").e(e) { "Error closing Couchbase vault: $vaultName" }
        }
        db = null
    }
    private fun setupCollections() {
        val d = db ?: return
        try {
            val scope = "_default"
            listOf(
                "sessions","doses","timelineEvents","notes",
                "substances","effects","interactions",
                "links","persons","attachments","devices","syncConfigs"
            ).forEach { d.createCollection(it, scope) }
            Log.withTag("DB").d { "Setup collections for $vaultName" }
        } catch (e: Exception) {
            Log.withTag("DB").e(e) { "Failed to setup collections for $vaultName" }
            throw e
        }
    }
}
