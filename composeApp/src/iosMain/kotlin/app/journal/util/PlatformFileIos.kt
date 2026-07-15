package app.journal.util

import platform.Foundation.*
import app.journal.log.Log

/**
 * iOS platform file operations using NSFileManager.
 */
actual object PlatformFile {
    private val fileManager = NSFileManager.defaultManager

    actual fun writeText(path: String, content: String) {
        try {
            val parent = NSString.stringWithString(path).stringByDeletingLastPathComponent
            fileManager.createDirectoryAtPath(parent, withIntermediateDirectories = true,
                attributes = null, error = null)
            (content as NSString).writeToFile(path, atomically = true,
                encoding = NSUTF8StringEncoding, error = null)
        } catch (e: Exception) {
            Log.withTag("PlatformFile").e(e) { "Failed to write file: $path" }
        }
    }

    actual fun readText(path: String): String {
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: throw Error("File not found or unreadable: $path")
    }

    actual fun dataDir(): String {
        val docs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String ?: NSTemporaryDirectory()
        return "$docs/.psychonautica"
    }
}
