package app.journal.util

import app.journal.log.Log
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*

// Protocol type alias — removed from kotlinx.cinterop in Kotlin 2.4.0
private typealias Protocol = Any

@OptIn(ExperimentalForeignApi::class)
actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.Main) {
        try {
            val controller = UIDocumentPickerViewController(
                forExportingURLs = listOf(NSURL(fileURLWithPath = NSTemporaryDirectory() + defaultName)),
                asCopy = true
            )
            var resultUrl: String? = null
            controller.delegate = SaveFileDelegate { url -> resultUrl = url }
            presentViewController(controller)
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
                forOpeningContentTypes = if (types.isNotEmpty()) types else listOf(UTType.typeWithIdentifier("public.data")),
                asCopy = true
            )
            var resultUrl: String? = null
            controller.delegate = SaveFileDelegate { url -> resultUrl = url }
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

internal fun fallbackExportPath(fileName: String): String {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: NSTemporaryDirectory()
    return "$docs/$fileName"
}

@OptIn(ExperimentalForeignApi::class)
private class SaveFileDelegate(
    private val onResult: (String?) -> Unit
) : UIDocumentPickerDelegateProtocol {
    override fun isEqual(`object`: Any?): Boolean = false
    override fun `class`(): ObjCClass? = null
    @Suppress("CONFLICTING_OVERLOADS")
    override fun conformsToProtocol(aProtocol: Protocol?): Boolean = false
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onResult(didPickDocumentsAtURLs.firstOrNull()?.let { (it as? NSURL)?.path })
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}
