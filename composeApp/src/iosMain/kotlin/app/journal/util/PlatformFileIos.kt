package app.journal.util

actual object PlatformFile {
    actual fun writeText(path: String, content: String) {
        // iOS: write to file using Foundation APIs
    }

    actual fun readText(path: String): String {
        // iOS: read from file using Foundation APIs
        return ""
    }

    actual fun dataDir(): String {
        // iOS: use NSDocumentDirectory
        return "."
    }
}
