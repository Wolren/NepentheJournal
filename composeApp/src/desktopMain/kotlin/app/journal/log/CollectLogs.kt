package app.journal.log

import java.io.File

actual fun collectLogs(appDir: String): String {
    return try {
        val dir = File(appDir)
        val latest = dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }

        if (latest != null && latest.exists()) {
            latest.readText()
        } else {
            "No crash logs found in $appDir"
        }
    } catch (e: Exception) {
        "Error reading crash logs: ${e.message}"
    }
}
