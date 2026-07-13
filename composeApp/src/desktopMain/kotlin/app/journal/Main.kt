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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val crashDir = java.io.File(appDataDir, "crashlogs")
        crashDir.mkdirs()
        val crashFile = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.dump")
        crashFile.writeText(
            "Thread: ${thread.name}\n${throwable.stackTraceToString()}"
        )
        // Also try to flush via the logging system
        app.journal.log.Log.withTag("JVM").e(throwable) { "Uncaught exception on ${thread.name}" }
    }

    // Scope for debounced auto-save (lives as long as the app)
    val autoSaveScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    application {
        val repo = JournalRepository.instance
        DataInitializer.ensureInitialized(repo, autoSaveScope)

        val icon = remember { loadAppIcon() }
        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 820.dp),
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
