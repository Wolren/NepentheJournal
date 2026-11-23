package app.journal

import android.app.Application
import android.content.Context
import android.os.Build
import app.journal.log.Log
import app.journal.log.MainThreadStalls
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
        // A freeze users only describe as "lag" needs a stack, and the log is the only surface they can export.
        MainThreadStalls.start()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = java.io.File(filesDir, "crashlogs")
                crashDir.mkdirs()
                val crashFile = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.dump")
                // A release stack is R8-mangled, so the build that wrote it must travel with the dump.
                crashFile.writeText(
                    "App: Nepenthe Journal ${versionLabel(this)}\n" +
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${thread.name}\n\n" +
                        throwable.stackTraceToString()
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

        /** versionName plus versionCode, so a dump names the exact build that produced it. */
        fun versionLabel(context: Context): String = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
