package app.journal.util

import java.io.File

actual object PlatformFile {
    actual fun writeText(path: String, content: String) {
        File(path).writeText(content)
    }

    actual fun readText(path: String): String {
        return File(path).readText()
    }

    actual fun dataDir(): String {
        return System.getProperty("user.home")?.let { "$it/.psychonautica" }
            ?: throw IllegalStateException("Cannot determine home directory")
    }
}
