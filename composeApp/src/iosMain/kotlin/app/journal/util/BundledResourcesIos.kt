package app.journal.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.ObjCName
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
actual fun readBundledResource(path: String): String? {
    return try {
        val cleanPath = path.trimStart('/')
        val name = cleanPath.substringBeforeLast('.')
        val ext = cleanPath.substringAfterLast('.', "")
        // Try main bundle first (app resource), then framework bundle
        val filePath = NSBundle.mainBundle.pathForResource(name, ofType = ext)
            ?: NSBundle.bundleForClass(BundledResourceAnchor::class.objcClass).pathForResource(name, ofType = ext)
        if (filePath != null) {
            NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null)
        } else null
    } catch (_: Exception) { null }
}

/** Anchor class to locate the framework bundle at runtime. */
@ObjCName("BundledResourceAnchor")
class BundledResourceAnchor
