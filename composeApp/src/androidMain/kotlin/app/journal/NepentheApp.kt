package app.journal

import android.app.Application
import android.content.Context
import com.couchbase.lite.CouchbaseLite

/**
 * Application class for Nepenthe Journal.
 *
 * Responsibilities:
 * 1. Store reference to application context for platform components
 *    (JournalStore, ContentResolver-based file operations).
 * 2. Call CouchbaseLite.init(context) once per process
 *    (required before any DatabaseProvider usage).
 * 3. Initialize AndroidFilePicker hook for ActivityResult-based file dialogs.
 */
class NepentheApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CouchbaseLite.init(this)
    }

    companion object {
        lateinit var instance: NepentheApp
            private set

        /** Convenience accessor for app-level Context. */
        val appContext: Context get() = instance.applicationContext
    }
}
