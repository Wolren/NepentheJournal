package app.journal.log

import app.journal.util.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIDevice

/**
 * iOS collectLogs: builds the same diagnostics bundle as Android/desktop
 * (rolling nepenthe.log files plus crashlogs/ dumps) for the diagnostics
 * card. Returns an empty string only when nothing is available.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun collectLogs(appDir: String): String {
    return try {
        val fileManager = NSFileManager.defaultManager
        val sb = StringBuilder()

        sb.appendLine("=== Nepenthe Journal Crash Log Bundle ===")
        sb.appendLine("Generated: ${currentTimeMillis()}")
        val device = UIDevice.currentDevice
        sb.appendLine("Device: ${device.model ?: "unknown"} (${device.systemName ?: "iOS"} ${device.systemVersion ?: ""})")
        val version = (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String)
            ?: (NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as? String)
            ?: "unknown"
        sb.appendLine("App version: $version")
        sb.appendLine()

        val names = fileManager.contentsOfDirectoryAtPath(appDir, error = null)
            ?.filterIsInstance<NSString>()
            ?.map { it as String }
            ?: emptyList()
        val logFiles = names
            .filter { it.startsWith("nepenthe.log") && (it == "nepenthe.log" || it.matches(Regex("nepenthe\\.log\\.\\d+"))) }
            .sortedWith(compareBy { if (it == "nepenthe.log") 0 else it.removePrefix("nepenthe.log.").toIntOrNull() ?: Int.MAX_VALUE })

        if (logFiles.isNotEmpty()) {
            sb.appendLine("--- Rolling log files (${logFiles.size} file(s)) ---")
            for (name in logFiles) {
                val path = "$appDir/$name"
                val size = (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
                sb.appendLine(">>> $name ($size bytes)")
                val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as? String
                sb.appendLine((content ?: "<unreadable>").takeLast(500_000))
                sb.appendLine()
            }
        } else {
            sb.appendLine("No rolling log files found in $appDir")
        }

        val crashNames = fileManager.contentsOfDirectoryAtPath("$appDir/crashlogs", error = null)
            ?.filterIsInstance<NSString>()
            ?.map { it as String }
            ?.filter { it.endsWith(".dump") || it.endsWith(".log") }
            ?.sortedDescending()
            ?: emptyList()
        if (crashNames.isNotEmpty()) {
            val dateFormatter = NSDateFormatter()
            dateFormatter.dateFormat = "yyyy-MM-dd HH:mm"
            sb.appendLine("=== Crash dumps (${crashNames.size} file(s)) ===")
            for (name in crashNames.take(20)) {
                val path = "$appDir/crashlogs/$name"
                val attrs = fileManager.attributesOfItemAtPath(path, null)
                val size = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
                val modified = (attrs?.get(NSFileModificationDate) as? NSDate)?.let { dateFormatter.stringFromDate(it) } ?: "unknown"
                sb.appendLine(">>> $name ($size bytes, $modified)")
                val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as? String
                if (content != null && content.length < 500_000) {
                    sb.appendLine(content)
                } else if (content != null) {
                    sb.appendLine(content.take(500_000))
                    sb.appendLine("...(truncated at 500KB)")
                } else {
                    sb.appendLine("<unreadable>")
                }
                sb.appendLine()
            }
            if (crashNames.size > 20) {
                sb.appendLine("... and ${crashNames.size - 20} older crash dumps not included in this export")
            }
        } else {
            sb.appendLine("No crash dumps found")
        }

        sb.toString()
    } catch (e: Exception) {
        "Error collecting crash logs: ${e.message}"
    }
}

/**
 * Delete crash dump files older than [maxAgeDays] days.
 */
@OptIn(ExperimentalForeignApi::class)
fun cleanOldCrashLogs(appDir: String, maxAgeDays: Int = 30) {
    try {
        val fileManager = NSFileManager.defaultManager
        val crashDir = "$appDir/crashlogs"
        val names = fileManager.contentsOfDirectoryAtPath(crashDir, error = null)
            ?.filterIsInstance<NSString>()
            ?.map { it as String }
            ?.filter { it.endsWith(".dump") || it.endsWith(".log") }
            ?: return
        val cutoff = currentTimeMillis() - maxAgeDays * 86400000L
        var deleted = 0
        for (name in names) {
            val path = "$crashDir/$name"
            val modified = (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970?.times(1000)?.toLong()
                ?: continue
            if (modified < cutoff && fileManager.removeItemAtPath(path, null)) deleted++
        }
        if (deleted > 0) {
            Log.withTag("CleanLogs").i { "Deleted $deleted old crash log(s) from $crashDir" }
        }
    } catch (e: Exception) {
        Log.withTag("CleanLogs").w { "Failed to clean old crash logs: ${e.message}" }
    }
}

/**
 * Write a crash dump into crashlogs/. Used by the uncaught exception hook
 * and available for manual crash reports.
 */
@OptIn(ExperimentalForeignApi::class)
fun writeCrashLog(appDir: String, threadName: String, detail: String) {
    try {
        val fileManager = NSFileManager.defaultManager
        val crashDir = "$appDir/crashlogs"
        fileManager.createDirectoryAtPath(crashDir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$crashDir/crash-${currentTimeMillis()}.dump"
        (("Thread: $threadName\n$detail") as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    } catch (_: Exception) {
    }
}

/** Directory the uncaught exception hook writes crash dumps to. */
@Volatile
private var crashReportDir: String? = null

/**
 * Install a best-effort uncaught ObjC exception hook that writes a .dump
 * file so the crash survives for the diagnostics card. Kotlin/Native fatal
 * errors that bypass ObjC exceptions are not caught here; use
 * [writeCrashLog] for manual reports.
 */
@OptIn(ExperimentalForeignApi::class)
fun installCrashHandler(appDir: String) {
    crashReportDir = appDir
    try {
        NSSetUncaughtExceptionHandler(staticCFunction { exception ->
            try {
                val dir = crashReportDir ?: return@staticCFunction
                val detail = buildString {
                    append("name: ${exception?.name ?: "unknown"}\n")
                    append("reason: ${exception?.reason ?: "unknown"}\n")
                    val symbols = exception?.callStackSymbols ?: emptyList<Any?>()
                    for (symbol in symbols) append(symbol.toString()).append("\n")
                }
                val path = "$dir/crashlogs"
                NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
                (("Uncaught exception\n$detail") as NSString).writeToFile(
                    "$path/crash-${NSDate().timeIntervalSince1970.toLong() * 1000}.dump",
                    atomically = true, encoding = NSUTF8StringEncoding, error = null
                )
            } catch (_: Exception) {
            }
        })
    } catch (_: Exception) {
    }
}
