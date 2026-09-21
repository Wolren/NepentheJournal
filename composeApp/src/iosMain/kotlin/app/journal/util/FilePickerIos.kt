package app.journal.util

import app.journal.log.Log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import kotlin.coroutines.resume

private interface Protocol

@OptIn(ExperimentalForeignApi::class)
actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                val controller = UIDocumentPickerViewController(
                    forExportingURLs = listOf(NSURL(fileURLWithPath = NSTemporaryDirectory() + defaultName)),
                    asCopy = true
                )
                val delegate = ResultDelegate { url ->
                    controller.dismissViewControllerAnimated(true, completion = null)
                    if (cont.isActive) cont.resume(url)
                }
                // The picker keeps only a weak delegate, so retain it in
                // liveDelegates until the picker finishes or is cancelled.
                cont.invokeOnCancellation {
                    controller.dismissViewControllerAnimated(true, completion = null)
                }
                controller.delegate = delegate
                keepAlive(delegate)
                presentViewController(controller) ?: run {
                    dropAlive(delegate)
                    if (cont.isActive) cont.resume(null)
                }
            } catch (e: Exception) {
                Log.withTag("FilePicker").e(e) { "Failed to open save file dialog" }
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    actual suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                val types = extensions.mapNotNull { UTType.typeWithFilenameExtension(it) }
                val controller = UIDocumentPickerViewController(
                    forOpeningContentTypes = if (types.isNotEmpty()) types else listOf(UTType.typeWithIdentifier("public.data")),
                    asCopy = true
                )
                val delegate = ResultDelegate { url ->
                    controller.dismissViewControllerAnimated(true, completion = null)
                    if (cont.isActive) cont.resume(url)
                }
                cont.invokeOnCancellation {
                    controller.dismissViewControllerAnimated(true, completion = null)
                }
                controller.delegate = delegate
                keepAlive(delegate)
                presentViewController(controller) ?: run {
                    dropAlive(delegate)
                    if (cont.isActive) cont.resume(null)
                }
            } catch (e: Exception) {
                Log.withTag("FilePicker").e(e) { "Failed to open file dialog" }
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    actual suspend fun openFolder(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                val folderType = UTType.typeWithIdentifier("public.folder")
                val controller = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(folderType),
                    asCopy = true
                )
                val delegate = ResultDelegate { url ->
                    controller.dismissViewControllerAnimated(true, completion = null)
                    if (cont.isActive) cont.resume(url)
                }
                cont.invokeOnCancellation {
                    controller.dismissViewControllerAnimated(true, completion = null)
                }
                controller.delegate = delegate
                keepAlive(delegate)
                presentViewController(controller) ?: run {
                    dropAlive(delegate)
                    if (cont.isActive) cont.resume(null)
                }
            } catch (e: Exception) {
                Log.withTag("FilePicker").e(e) { "Failed to open folder dialog" }
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Resolve the presenting view controller from the connected scenes,
     * walking to the topmost presented controller. Never uses the
     * deprecated keyWindow, which is null in multi-scene apps.
     * Returns null when no foreground window exists.
     */
    private fun presentViewController(controller: UIViewController): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
        val windows = scenes.flatMap { it.windows }.filterIsInstance<UIWindow>()
        val window = windows.firstOrNull { it.keyWindow }
            ?: windows.firstOrNull { it.rootViewController != null }
            ?: return null
        var top = window.rootViewController ?: return null
        while (top.presentedViewController != null) {
            top = top.presentedViewController!!
        }
        top.presentViewController(controller, animated = true, completion = null)
        return top
    }

    /** Strong refs for live delegates; UIDocumentPicker keeps only a weak delegate. */
    private val liveDelegates = mutableSetOf<ResultDelegate>()

    private fun keepAlive(delegate: ResultDelegate) {
        liveDelegates.add(delegate)
    }

    private fun dropAlive(delegate: ResultDelegate) {
        liveDelegates.remove(delegate)
    }

    /**
     * One-shot delegate: resumes the awaiting suspend function from the
     * picker callbacks (pick or cancel) instead of returning a stale value.
     */
    private inner class ResultDelegate(
        private val onResult: (String?) -> Unit
    ) : UIDocumentPickerDelegateProtocol {
        private var resumed = false

        private fun finish(url: String?) {
            if (resumed) return
            resumed = true
            dropAlive(this)
            onResult(url)
        }

        override fun isEqual(`object`: Any?): Boolean = false
        override fun `class`(): ObjCClass? = null
        override fun isProxy(): Boolean = false
        override fun isKindOfClass(aClass: ObjCClass?): Boolean = false
        override fun isMemberOfClass(aClass: ObjCClass?): Boolean = false
        override fun conformsToProtocol(aProtocol: Protocol?): Boolean = false
        override fun respondsToSelector(aSelector: CPointer<out CPointed>?): Boolean = false
        override fun performSelector(aSelector: CPointer<out CPointed>?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?, _withObject: Any?): Any? = null
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            finish(didPickDocumentsAtURLs.firstOrNull()?.let { (it as? NSURL)?.path })
        }
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            finish(null)
        }
    }
}

internal fun fallbackExportPath(fileName: String): String {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: NSTemporaryDirectory()
    return "$docs/$fileName"
}
