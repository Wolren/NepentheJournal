package app.journal.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.ObjCName
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCName::class)
actual fun readBundledResource(path: String): String? {
    return try {
        val cleanPath = path.trimStart('/')
        val name = cleanPath.substringBeforeLast('.')
        val ext = cleanPath.substringAfterLast('.', "")
        // On iOS all bundled resources are in the main app bundle
        val filePath = NSBundle.mainBundle.pathForResource(name, ofType = ext)
        if (filePath != null) {
            NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
        } else null
    } catch (_: Exception) { null }
}

/** Anchor class to locate the framework bundle at runtime. */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@ObjCName("BundledResourceAnchor")
class BundledResourceAnchor
