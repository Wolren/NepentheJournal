package app.journal.util

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        // In-flight guard: a second pick must not orphan the first continuation.
        if (AndroidFilePickerBridge.continuation != null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        AndroidFilePickerBridge.continuation = cont
        cont.invokeOnCancellation {
            if (AndroidFilePickerBridge.continuation === cont) AndroidFilePickerBridge.reset()
        }
        val launcher = AndroidFilePickerBridge.launchCreateDocument
        if (launcher != null) {
            launcher("*/*", defaultName)
        } else {
            // Launcher not set up yet - return null
            AndroidFilePickerBridge.continuation = null
            cont.resume(null)
        }
    }

    actual suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        if (AndroidFilePickerBridge.continuation != null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        AndroidFilePickerBridge.continuation = cont
        cont.invokeOnCancellation {
            if (AndroidFilePickerBridge.continuation === cont) AndroidFilePickerBridge.reset()
        }
        val launcher = AndroidFilePickerBridge.launchOpenDocument
        if (launcher != null) {
            launcher(arrayOf("*/*"))
        } else {
            AndroidFilePickerBridge.continuation = null
            cont.resume(null)
        }
    }

    actual suspend fun openFolder(): String? = suspendCancellableCoroutine { cont ->
        if (AndroidFilePickerBridge.continuation != null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        AndroidFilePickerBridge.continuation = cont
        cont.invokeOnCancellation {
            if (AndroidFilePickerBridge.continuation === cont) AndroidFilePickerBridge.reset()
        }
        val launcher = AndroidFilePickerBridge.launchOpenFolder
        if (launcher != null) {
            launcher()
        } else {
            AndroidFilePickerBridge.continuation = null
            cont.resume(null)
        }
    }
}
