package app.journal.util

/**
 * Bridge between the composable ActivityResultLauncher registration
 * (which must happen in a Composable scope) and the suspend-function
 * FilePicker API (which runs in a coroutine).
 *
 * MainActivity sets up the launchers via rememberLauncherForActivityResult
 * and stores them here. FilePickerAndroid reads them and bridges via
 * suspendCancellableCoroutine.
 */
object AndroidFilePickerBridge {
    /** Callback invoked to launch a "create document" picker with a mime type and file name. */
    var launchCreateDocument: ((mimeType: String, defaultName: String) -> Unit)? = null

    /** Callback invoked to launch an "open document" picker with mime type filters. */
    var launchOpenDocument: ((mimeTypes: Array<String>) -> Unit)? = null

    /** Continuation to resume with the picked content URI string (or null on cancel). */
    @Volatile
    var continuation: kotlin.coroutines.Continuation<String?>? = null

    /** Resets state after picker completes. */
    fun reset() {
        continuation = null
    }
}
