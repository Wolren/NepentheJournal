package app.journal

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.log.initLogging
import app.journal.ui.App
import app.journal.ui.WindowControls
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

@OptIn(ExperimentalComposeUiApi::class)
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
        // The window is borderless, so the rail carries the window controls and F11 covers the screen.
        var fullscreen by remember { mutableStateOf(false) }
        var lastPlacement by remember { mutableStateOf<WindowPlacement>(WindowPlacement.Floating) }
        var floatingSize by remember { mutableStateOf(windowState.size) }
        var floatingPosition by remember { mutableStateOf(windowState.position) }

        remember {
            WindowControls.minimize = { windowState.isMinimized = true }
            WindowControls.toggleMaximize = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                    else WindowPlacement.Maximized
            }
            WindowControls.close = { exitApplication() }
            true
        }
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.placement }.collect { placement ->
                WindowControls.maximized = placement == WindowPlacement.Maximized
            }
        }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(fullscreen, windowState.placement, windowState.position to windowState.size) }
                .collectLatest { (isFullscreen, placement, geometry) ->
                    if (isFullscreen) return@collectLatest
                    lastPlacement = placement
                    // A maximized or full-screen window would persist as an edge-to-edge floating window.
                    if (placement != WindowPlacement.Floating) return@collectLatest
                    floatingPosition = geometry.first
                    floatingSize = geometry.second
                    delay(400)
                    WindowGeometryStore.save(geometry.first, geometry.second)
                }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Nepenthe Journal",
            icon = icon,
            decoration = WindowDecoration.Undecorated(),
            onPreviewKeyEvent = { event ->
                // KeyUp fires once per press; KeyDown repeats while the key is held and would flip the state.
                if (event.type == KeyEventType.KeyUp && event.key == Key.F11) {
                    fullscreen = !fullscreen
                    true
                } else false
            }
        ) {
            LaunchedEffect(fullscreen) {
                val device = window.graphicsConfiguration.device
                // First composition is not a toggle: nothing to enter or leave yet.
                if (!fullscreen && device.fullScreenWindow !== window &&
                    windowState.placement != WindowPlacement.Fullscreen
                ) return@LaunchedEffect
                if (fullscreen) {
                    windowState.placement = WindowPlacement.Floating
                    try {
                        if (device.isFullScreenSupported) device.fullScreenWindow = window
                        else windowState.placement = WindowPlacement.Fullscreen
                    } catch (e: Exception) {
                        Log.withTag("Window").w(e) { "full screen exclusive mode failed" }
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                } else {
                    if (device.fullScreenWindow === window) device.fullScreenWindow = null
                    windowState.placement = lastPlacement
                    windowState.size = floatingSize
                    windowState.position = floatingPosition
                }
            }
            Box(Modifier.fillMaxSize()) {
                App()
                // No title bar: the top 12 dp is the drag handle for moving the window.
                if (!fullscreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    window.setLocation(
                                        window.x + dragAmount.x.toInt(),
                                        window.y + dragAmount.y.toInt()
                                    )
                                }
                            }
                    )
                }
            }
        }
    }
}
