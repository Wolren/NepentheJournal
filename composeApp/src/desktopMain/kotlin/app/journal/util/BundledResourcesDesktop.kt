package app.journal.util

actual fun readBundledResource(path: String): String? {
    return try {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path.trimStart('/'))
        stream?.reader()?.readText()
    } catch (_: Exception) { null }
}
