package app.journal.util

import java.io.File

actual object PlatformFile {
    actual fun writeText(path: String, content: String) {
        File(path).writeText(content)
    }

    actual fun readText(path: String): String {
        return File(path).readText()
    }

    actual fun writeBytes(path: String, content: ByteArray) {
        File(path).writeBytes(content)
    }

    actual fun readBytes(path: String): ByteArray {
        return File(path).readBytes()
    }

    actual fun size(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else -1L
    }

    actual fun dataDir(): String {
        return System.getProperty("user.home")?.let { "$it/.nepenthe" }
            ?: throw IllegalStateException("Cannot determine home directory")
    }

    actual fun copyToLocal(source: String, target: String): Boolean {
        return try {
            val out = File(target)
            out.parentFile?.mkdirs()
            File(source).copyTo(out, overwrite = true)
            out.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    actual fun delete(target: String): Boolean {
        return try {
            val file = File(target)
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
    }
}
