package app.journal.util

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        AndroidFilePickerBridge.continuation = cont
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
        AndroidFilePickerBridge.continuation = cont
        val launcher = AndroidFilePickerBridge.launchOpenDocument
        if (launcher != null) {
            launcher(arrayOf("*/*"))
        } else {
            AndroidFilePickerBridge.continuation = null
            cont.resume(null)
        }
    }
}
