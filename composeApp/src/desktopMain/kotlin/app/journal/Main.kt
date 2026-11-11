package app.journal

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.log.initLogging
import app.journal.ui.App
import app.journal.util.NativeFullscreen
import app.journal.util.WindowGeometryStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

private fun loadAppIcon(): Painter {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("icon_64.png")?.readBytes() ?: ByteArray(0)
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main() {
    System.setProperty("skiko.renderApi", "SOFTWARE")
    val appDataDir = System.getProperty("user.home")?.let { "$it/.nepenthe" }
    if (appDataDir != null) {
        // Belt to initLogging's suspenders: the dir must exist before any writer, crash dump, or store file touches it.
        try {
            java.io.File(appDataDir).mkdirs()
        } catch (_: Exception) { /* initLogging still adds the console writer */ }
    }
    initLogging(appDataDir)

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
        try {
            app.journal.log.Log.withTag("JVM").e(throwable) { "Uncaught exception on ${thread.name}" }
        } catch (_: Exception) { /* logging unavailable during crash */ }
        previousHandler?.uncaughtException(thread, throwable)
    }

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

    val autoSaveScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            app.journal.log.Log.withTag("JVM").e(e) { "Uncaught coroutine exception in autoSaveScope" }
        }
    )

    val repo = JournalRepository.instance
    autoSaveScope.launch {
        DataInitializer.ensureInitialized(repo, autoSaveScope)
    }

    application {
        val icon = remember { loadAppIcon() }
        val savedGeometry = remember { WindowGeometryStore.load() }
        val windowState = rememberWindowState(
            size = savedGeometry?.second ?: DpSize(1200.dp, 820.dp),
            position = savedGeometry?.first ?: WindowPosition(100.dp, 60.dp)
        )
        // F11 strips the frame through user32; the rest of the time the window keeps its system title bar.
        var fullscreen by remember { mutableStateOf(false) }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(fullscreen, windowState.placement, windowState.position to windowState.size) }
                .collectLatest { (isFullscreen, placement, geometry) ->
                    // A maximized or full-screen window would persist as an edge-to-edge floating window.
                    if (isFullscreen || placement != WindowPlacement.Floating) return@collectLatest
                    delay(400)
                    WindowGeometryStore.save(geometry.first, geometry.second)
                }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Nepenthe Journal",
            icon = icon,
            onPreviewKeyEvent = { event ->
                // KeyUp fires once per press; KeyDown repeats while the key is held and would flip the state.
                if (event.type == KeyEventType.KeyUp && event.key == Key.F11) {
                    fullscreen = !fullscreen
                    true
                } else false
            }
        ) {
            val borderless = remember { NativeFullscreen(window) }
            LaunchedEffect(fullscreen) {
                if (fullscreen) {
                    if (!borderless.enter()) {
                        Log.withTag("Window").w { "native full screen unavailable" }
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                } else {
                    borderless.exit()
                    if (windowState.placement == WindowPlacement.Fullscreen) {
                        windowState.placement = WindowPlacement.Floating
                    }
                }
            }
            App()
        }
    }
}
