package app.journal

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.log.initLogging
import app.journal.ui.App
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

private fun loadAppIcon(): Painter {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("icon_64.png")?.readBytes() ?: ByteArray(0)
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main() {
    // Force Skiko to software rendering (hardware accel may fail in some environments)
    System.setProperty("skiko.renderApi", "SOFTWARE")
    // Initialize logging
    val appDataDir = System.getProperty("user.home")?.let { "$it/.psychonautica" }
    initLogging(appDataDir)

    // Global uncaught exception handler -- writes to a separate file so crash
    // details survive even if the rolling log writer is mid-flush during a crash.
    // Always chains to the previous handler so the crash is still reported
    // instead of being silently swallowed.
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val crashDir = java.io.File(appDataDir, "crashlogs")
            crashDir.mkdirs()
            val crashFile = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.dump")
            crashFile.writeText(
                "Thread: ${thread.name}\n${throwable.stackTraceToString()}"
            )
        } catch (_: Exception) { /* best effort: crash dump must not block chaining */ }
        // Also try to flush via the logging system
        try {
            app.journal.log.Log.withTag("JVM").e(throwable) { "Uncaught exception on ${thread.name}" }
        } catch (_: Exception) { /* logging unavailable during crash */ }
        previousHandler?.uncaughtException(thread, throwable)
    }

    // Flush in-memory data on JVM shutdown (Ctrl+C, taskkill, kill).
    // The Compose onDispose path covers clean window close; this covers
    // everything else so at most the 2s autosave debounce window is lost.
    // Best effort: logging may be mid-flush during shutdown (audit S2).
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            app.journal.data.JournalStore(JournalRepository.instance).save()
        } catch (e: Exception) {
            try {
                app.journal.log.Log.withTag("JVM").e(e) { "Shutdown save failed" }
            } catch (_: Exception) { /* logging unavailable during shutdown */ }
        }
    })

    // Scope for debounced auto-save (lives as long as the app).
    // CoroutineExceptionHandler logs failures instead of dropping them silently.
    val autoSaveScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            app.journal.log.Log.withTag("JVM").e(e) { "Uncaught coroutine exception in autoSaveScope" }
        }
    )

    // Heavy init (megabytes of seed plus DoseWiki JSON) runs off the main
    // thread; the window opens immediately and App gates on
    // DataInitializer.initializedFlow with a loading screen.
    val repo = JournalRepository.instance
    autoSaveScope.launch {
        DataInitializer.ensureInitialized(repo, autoSaveScope)
    }

    application {
        val icon = remember { loadAppIcon() }
        val windowState = rememberWindowState(
            size = DpSize(1100.dp, 820.dp),
            position = WindowPosition(100.dp, 60.dp)
        )

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Nepenthe Journal",
            icon = icon
        ) { App() }
    }
}
