package app.journal

import android.app.Application
import android.content.Context
import app.journal.log.Log
import app.journal.log.initLogging

/**
 * Application class for Nepenthe Journal.
 *
 * Responsibilities:
 * 1. Store reference to application context for platform components
 *    (JournalStore, ContentResolver-based file operations).
 * 2. Initialize AndroidFilePicker hook for ActivityResult-based file dialogs.
 */
class NepentheApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        initLogging(filesDir.absolutePath)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = java.io.File(filesDir, "crashlogs")
                crashDir.mkdirs()
                val crashFile = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.dump")
                crashFile.writeText(
                    "Thread: ${thread.name}\n${throwable.stackTraceToString()}"
                )
            } catch (e: Exception) {
                Log.withTag("NepentheApp").e(e) { "Failed to write crash dump" }
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        lateinit var instance: NepentheApp
            private set

        val appContext: Context get() = instance.applicationContext
    }
}
