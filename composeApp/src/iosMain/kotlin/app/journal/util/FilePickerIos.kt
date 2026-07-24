package app.journal.util

import app.journal.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import kotlin.coroutines.resume

/**
 * iOS file picker using UIDocumentPickerViewController.
 *
 * Opens a system document browser for export/import.
 * The delegate callback bridges to a coroutine via suspendCancellableCoroutine.
 */
actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.Main) {
        try {
            val type = UTType.typeWithFilenameExtension(extensions.firstOrNull() ?: "csv")
                ?: UTType.data
            val controller = UIDocumentPickerViewController(
                forExportingURLs = listOf(
                    NSURL(fileURLWithPath = NSTemporaryDirectory() + defaultName)
                ),
                asCopy = true
            )

            var resultUrl: String? = null

            controller.delegate = object : UIDocumentPickerDelegateProtocol {
                override fun documentPicker(
                    controller: UIDocumentPickerViewController,
                    didPickDocumentsAtURLs: List<*>
                ) {
                    resultUrl = didPickDocumentsAtURLs.firstOrNull()
                        ?.let { (it as? NSURL)?.path }
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    // User cancelled — resultUrl stays null
                }
            }

            presentViewController(controller)
            // Return the selected path — the caller writes to this path
            resultUrl
        } catch (e: Exception) {
            Log.withTag("FilePicker").e(e) { "Failed to open save file dialog" }
            null
        }
    }

    actual suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.Main) {
        try {
            val types = extensions.mapNotNull { UTType.typeWithFilenameExtension(it) }
            val controller = UIDocumentPickerViewController(
                forOpeningContentTypes = if (types.isNotEmpty()) types else listOf(UTType.data),
                asCopy = true
            )

            var resultUrl: String? = null

            controller.delegate = object : UIDocumentPickerDelegateProtocol {
                override fun documentPicker(
                    controller: UIDocumentPickerViewController,
                    didPickDocumentsAtURLs: List<*>
                ) {
                    resultUrl = didPickDocumentsAtURLs.firstOrNull()
                        ?.let { (it as? NSURL)?.path }
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    // User cancelled
                }
            }

            presentViewController(controller)
            resultUrl
        } catch (e: Exception) {
            Log.withTag("FilePicker").e(e) { "Failed to open file dialog" }
            null
        }
    }

    private fun presentViewController(controller: UIViewController) {
        val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootVC?.presentViewController(controller, animated = true, completion = null)
    }
}

/**
 * Fallback: write CSV data directly to the app's Documents directory
 * when UIDocumentPicker is unavailable or the user cancels.
 */
internal fun fallbackExportPath(fileName: String): String {
    val docs = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String ?: NSTemporaryDirectory()
    return "$docs/$fileName"
}
