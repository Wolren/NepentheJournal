package app.journal.util

import java.io.File

actual object PlatformFile {
    actual fun writeText(path: String, content: String) {
        File(path).writeText(content)
    }

    actual fun readText(path: String): String {
        return File(path).readText()
    }

    actual fun size(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else -1L
    }

    actual fun dataDir(): String {
        return System.getProperty("user.home")?.let { "$it/.nepenthe" }
            ?: throw IllegalStateException("Cannot determine home directory")
    }
}
