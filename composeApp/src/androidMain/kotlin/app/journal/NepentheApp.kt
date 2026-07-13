package app.journal

import android.app.Application
import android.content.Context
import app.journal.log.Log
import app.journal.log.initLogging
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
        try {
            CouchbaseLite.init(this)
        } catch (e: Exception) {
            Log.withTag("Android").e(e) { "CouchbaseLite.init failed" }
        }
        initLogging(filesDir.absolutePath)

        // Global uncaught exception handler -- writes crash to a separate file
        // so it survives even if the rolling log writer is mid-flush.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashDir = java.io.File(filesDir, "crashlogs")
            crashDir.mkdirs()
            val crashFile = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.dump")
            crashFile.writeText(
                "Thread: ${thread.name}\n${throwable.stackTraceToString()}"
            )
        }
    }

    companion object {
        lateinit var instance: NepentheApp
            private set

        /** Convenience accessor for app-level Context. */
        val appContext: Context get() = instance.applicationContext
    }
}
