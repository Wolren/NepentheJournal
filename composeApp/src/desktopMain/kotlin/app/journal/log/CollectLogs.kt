package app.journal.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

actual fun collectLogs(appDir: String): String {
    return try {
        val sb = StringBuilder()

        // Header with app info
        sb.appendLine("=== Nepenthe Journal Crash Log Bundle ===")
        sb.appendLine("Generated: ${System.currentTimeMillis()}")
        sb.appendLine("OS: ${System.getProperty("os.name", "unknown")} ${System.getProperty("os.version", "")}")
        sb.appendLine("Java: ${System.getProperty("java.version", "unknown")}")
        val pkg = Package.getPackage("app.journal")
        sb.appendLine("App version: ${pkg?.implementationVersion ?: "unknown"}")
        sb.appendLine()

        // Collect rolling log files (nepenthe.log, nepenthe.log.1, nepenthe.log.2, ...)
        val logDir = File(appDir)
        if (logDir.isDirectory()) {
            val logFiles = logDir.listFiles()
                ?.filter { it.name.startsWith("nepenthe.log") && (it.name == "nepenthe.log" || it.name.matches(Regex("nepenthe\\.log\\.\\d+"))) }
                ?.sortedBy { f ->
                    when {
                        f.name == "nepenthe.log" -> 0
                        else -> f.name.removePrefix("nepenthe.log.").toIntOrNull() ?: Int.MAX_VALUE
                    }
                } ?: emptyList()

            if (logFiles.isNotEmpty()) {
                sb.appendLine("--- Rolling log files (${logFiles.size} file(s)) ---")
                for (file in logFiles) {
                    sb.appendLine(">>> ${file.name} (${file.length()} bytes)")
                    if (file.length() < 10_000_000) {
                        sb.appendLine(file.readText().takeLast(500_000))
                    } else {
                        sb.appendLine(file.readText().takeLast(500_000))
                        // This caps each file to ~500KB in the export to prevent OOM
                    }
                    sb.appendLine()
                }
            } else {
                sb.appendLine("No rolling log files found in $appDir")
            }
        } else {
            sb.appendLine("Log directory $appDir does not exist")
        }

        // Collect crash dump files from crashlogs/ subdirectory
        val crashDir = File(appDir, "crashlogs")
        if (crashDir.isDirectory()) {
            val crashFiles = crashDir.listFiles()
                ?.filter { it.name.endsWith(".dump") || it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (crashFiles.isNotEmpty()) {
                sb.appendLine("=== Crash dumps (${crashFiles.size} file(s)) ===")
                for (file in crashFiles.take(20)) { // limit to 20 most recent crash dumps
                    sb.appendLine(">>> ${file.name} (${file.length()} bytes, ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(file.lastModified())})")
                    if (file.length() < 500_000) {
                        sb.appendLine(file.readText())
                    } else {
                        sb.appendLine(file.readText().take(500_000))
                        sb.appendLine("...(truncated at 500KB)")
                    }
                    sb.appendLine()
                }
                if (crashFiles.size > 20) {
                    sb.appendLine("... and ${crashFiles.size - 20} older crash dumps not included in this export")
                }
            } else {
                sb.appendLine("No crash dumps found")
            }
        } else {
            sb.appendLine("No crashlogs directory")
        }

        sb.toString()
    } catch (e: Exception) {
        "Error collecting crash logs: ${e.message}\n${e.stackTraceToString()}"
    }
}

/**
 * Delete crash dump files older than [maxAgeDays] days.
 * Call once at app startup to prevent unbounded accumulation.
 */
fun cleanOldCrashLogs(appDir: String, maxAgeDays: Int = 30) {
    try {
        val crashDir = File(appDir, "crashlogs")
        if (!crashDir.isDirectory()) return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86400000L
        var deleted = 0
        for (file in crashDir.listFiles()?.filter { it.name.endsWith(".dump") || it.name.endsWith(".log") } ?: emptyList()) {
            if (file.lastModified() < cutoff) {
                if (file.delete()) deleted++
            }
        }
        if (deleted > 0) {
            app.journal.log.Log.withTag("CleanLogs").i { "Deleted $deleted old crash log(s) from ${crashDir.path}" }
        }
    } catch (e: Exception) {
        app.journal.log.Log.withTag("CleanLogs").w { "Failed to clean old crash logs: ${e.message}" }
    }
}
