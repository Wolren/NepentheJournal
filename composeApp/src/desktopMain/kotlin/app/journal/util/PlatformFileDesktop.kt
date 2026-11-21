package app.journal.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

actual object PlatformFile {
    /**
     * Serialises readers against replacers: Windows refuses to rename over a
     * file another thread has open, which turned every concurrent save into a
     * failed write.
     */
    private val ioLock = ReentrantReadWriteLock()

    actual fun writeText(path: String, content: String) {
        writeAtomically(path, content.toByteArray(Charsets.UTF_8))
    }

    actual fun readText(path: String): String {
        return ioLock.read { File(path).readText() }
    }

    actual fun writeBytes(path: String, content: ByteArray) {
        writeAtomically(path, content)
    }

    actual fun readBytes(path: String): ByteArray {
        return ioLock.read { File(path).readBytes() }
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

    /**
     * Writes beside the target, flushes the bytes, then renames over it, so a
     * crash leaves the old file or the new one and never a half-written file.
     */
    private fun writeAtomically(path: String, bytes: ByteArray) {
        val target = File(path)
        val dir = target.absoluteFile.parentFile
        dir?.mkdirs()
        ioLock.write {
            val tmp = File(dir, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            replaceWithRetry(tmp, target)
        }
    }

    /** The rename can lose a race with a scanner or a reader outside this process. */
    private fun replaceWithRetry(tmp: File, target: File) {
        var failure: Exception? = null
        for (attempt in 0 until 4) {
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
                return
            } catch (e: Exception) {
                failure = e
                if (attempt < 3) Thread.sleep(40L)
            }
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("Cannot replace ${target.path}", failure ?: e)
        }
    }
}
