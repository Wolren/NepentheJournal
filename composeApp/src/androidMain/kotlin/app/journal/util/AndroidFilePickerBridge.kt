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
    var launchCreateDocument: ((mimeType: String, defaultName: String) -> Unit)? = null

    var launchOpenDocument: ((mimeTypes: Array<String>) -> Unit)? = null

    var launchOpenFolder: (() -> Unit)? = null

    @Volatile
    var continuation: kotlin.coroutines.Continuation<String?>? = null

    fun reset() {
        continuation = null
    }
}
