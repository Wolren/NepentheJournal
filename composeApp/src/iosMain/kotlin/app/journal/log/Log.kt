package app.journal.log

import app.journal.util.currentTimeMillis
import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.dataUsingEncoding
import platform.Foundation.seekToEndOfFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

actual fun initLogging(appDir: String?) {
    val writers = mutableListOf<LogWriter>(
        CommonWriter()
    )
    if (appDir != null) {
        writers.add(IosFileLogWriter(appDir))
        cleanOldCrashLogs(appDir, maxAgeDays = 30)
        installCrashHandler(appDir)
    }
    Logger.setLogWriters(writers)
    Logger.i("Logging initialized (iOS) - dir: $appDir")
}

/**
 * Kermit file writer for iOS mirroring the Android/desktop rolling file
 * config (nepenthe.log, 5MB per file, 10 rotating files). Kermit-io is not
 * wired into iosMain, so rotation is hand-rolled with NSFileManager.
 * Logging never throws: every IO failure is swallowed.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileLogWriter(private val appDir: String) : LogWriter() {

    private val fileManager = NSFileManager.defaultManager
    private val lock = platform.Foundation.NSRecursiveLock()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        try {
            val line = buildString {
                append(currentTimeMillis())
                append(" [").append(tag).append("] ")
                append(severity.name).append(": ")
                append(message)
                if (throwable != null) {
                    append("\n").append(runCatching { throwable.stackTraceToString() }.getOrNull() ?: throwable.toString())
                }
                append("\n")
            }
            lock.lock()
            try {
                rotateIfNeeded()
                appendLine(logPath(0), line)
            } finally {
                lock.unlock()
            }
        } catch (_: Exception) {
        }
    }

    private fun logPath(index: Int): String =
        if (index == 0) "$appDir/nepenthe.log" else "$appDir/nepenthe.log.$index"

    private fun fileSize(path: String): Long =
        (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L

    private fun rotateIfNeeded() {
        try {
            if (!fileManager.fileExistsAtPath(logPath(0))) return
            if (fileSize(logPath(0)) < ROLL_BYTES) return
            fileManager.removeItemAtPath(logPath(MAX_FILES), null)
            for (i in MAX_FILES - 1 downTo 0) {
                val from = logPath(i)
                if (fileManager.fileExistsAtPath(from)) {
                    fileManager.moveItemAtPath(from, toPath = logPath(i + 1), error = null)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendLine(path: String, line: String) {
        try {
            if (!fileManager.fileExistsAtPath(path)) {
                (line as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
                return
            }
            val data: NSData? = (line as NSString).dataUsingEncoding(NSUTF8StringEncoding)
                ?: return
            val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: run {
                // Fall back to a full rewrite when the handle cannot open.
                val existing = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as? String ?: ""
                ((existing + line) as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
                return
            }
            try {
                handle.seekToEndOfFile()
                handle.writeData(data!!, null)
            } finally {
                handle.closeFile()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 5MB per file, mirroring the Android/desktop rolling config. */
        const val ROLL_BYTES = 5L * 1024 * 1024

        /** Keep 10 rotating files, mirroring the Android/desktop config. */
        const val MAX_FILES = 10
    }
}
