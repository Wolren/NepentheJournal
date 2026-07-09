package app.journal.data
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
        CouchbaseLite.init()
        val cfg = DatabaseConfiguration().apply {
            directory = File(System.getProperty("user.home"), ".psychonautica").absolutePath
        }
        db = Database(vaultName, cfg)
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
