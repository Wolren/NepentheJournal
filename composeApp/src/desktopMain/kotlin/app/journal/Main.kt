package app.journal

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
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
    // Scope for debounced auto-save (lives as long as the app)
    val autoSaveScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    application {
        val repo = JournalRepository.instance
        DataInitializer.ensureInitialized(repo, autoSaveScope)

        val icon = remember { loadAppIcon() }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Nepenthe Journal",
            icon = icon
        ) { App() }
    }
}
