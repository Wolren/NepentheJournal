package app.journal.log

import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

actual fun collectLogs(appDir: String): String {
    return try {
        val sb = StringBuilder()

        sb.appendLine("=== Nepenthe Journal Crash Log Bundle ===")
        sb.appendLine("Generated: ${System.currentTimeMillis()}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        sb.appendLine()

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
                    val content = file.readText().takeLast(500_000)
                    sb.appendLine(content)
                    sb.appendLine()
                }
            } else {
                sb.appendLine("No rolling log files found in $appDir")
            }
        }

        val crashDir = File(appDir, "crashlogs")
        if (crashDir.isDirectory()) {
            val crashFiles = crashDir.listFiles()
                ?.filter { it.name.endsWith(".dump") || it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (crashFiles.isNotEmpty()) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                sb.appendLine("=== Crash dumps (${crashFiles.size} file(s)) ===")
                for (file in crashFiles.take(20)) {
                    sb.appendLine(">>> ${file.name} (${file.length()} bytes, ${dateFormat.format(file.lastModified())})")
                    val content = file.readText().take(500_000)
                    sb.appendLine(content)
                    sb.appendLine()
                }
                if (crashFiles.size > 20) {
                    sb.appendLine("... and ${crashFiles.size - 20} older crash dumps not included")
                }
            } else {
                sb.appendLine("No crash dumps found")
            }
        }

        sb.toString()
    } catch (e: Exception) {
        "Error collecting crash logs: ${e.message}\n${e.stackTraceToString()}"
    }
}

/**
 * Delete crash dump files older than [maxAgeDays] days.
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
            Log.withTag("CleanLogs").i { "Deleted $deleted old crash log(s) from ${crashDir.path}" }
        }
    } catch (e: Exception) {
        Log.withTag("CleanLogs").w { "Failed to clean old crash logs: ${e.message}" }
    }
}
