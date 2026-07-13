package app.journal.data
import app.journal.log.Log
import com.couchbase.lite.CouchbaseLite
import com.couchbase.lite.Database
import com.couchbase.lite.DatabaseConfiguration
import java.io.File

/**
 * JVM Desktop actual.
 * TLSIdentity for URLEndpointListener on JVM uses a PKCS12 KeyStore.
 * Store path: ~/.psychonautica/psychonautica-vault.cblite2
 * P2P: URLEndpointListener = Enterprise Edition only.
 *   A desktop node makes a natural hub; run listener + multiple replicators simultaneously.
 */
actual class DatabaseProvider actual constructor(private val vaultName: String) {
    private var db: Database? = null
    actual fun open() {
        try {
            CouchbaseLite.init()
            val cfg = DatabaseConfiguration().apply {
                directory = File(System.getProperty("user.home"), ".psychonautica").absolutePath
            }
            db = Database(vaultName, cfg)
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
