package app.journal.util

import android.content.ContentValues
import android.net.Uri
import android.provider.OpenableColumns
import app.journal.NepentheApp
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

actual object PlatformFile {
    /** Keeps a reader from seeing the file mid-replace, or the replacer racing it. */
    private val ioLock = ReentrantReadWriteLock()

    actual fun writeText(path: String, content: String) {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Cannot open output stream for $path")
        } else {
            ioLock.write { writeAtomically(path, content.toByteArray(Charsets.UTF_8)) }
        }
    }

    actual fun readText(path: String): String {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openInputStream(uri)?.use { `is` ->
                return `is`.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("Cannot open input stream for $path")
        } else {
            return ioLock.read { File(path).readText() }
        }
    }

    actual fun writeBytes(path: String, content: ByteArray) {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content)
            } ?: throw IllegalStateException("Cannot open output stream for $path")
        } else {
            ioLock.write { writeAtomically(path, content) }
        }
    }

    actual fun readBytes(path: String): ByteArray {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openInputStream(uri)?.use { input ->
                return input.readBytes()
            } ?: throw IllegalStateException("Cannot open input stream for $path")
        }
        return ioLock.read { File(path).readBytes() }
    }

    actual fun size(path: String): Long {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
                }
            }
            // Size unknown for this provider: let the caller decide (report -1).
            return -1L
        }
        val file = File(path)
        return if (file.exists()) file.length() else -1L
    }

    actual fun dataDir(): String {
        return NepentheApp.appContext.filesDir.absolutePath
    }

    actual fun copyToLocal(source: String, target: String): Boolean {
        val out = File(target)
        return try {
            out.parentFile?.mkdirs()
            if (source.startsWith("content://")) {
                val input = NepentheApp.appContext.contentResolver.openInputStream(Uri.parse(source))
                    ?: return false
                input.use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } }
            } else {
                File(source).copyTo(out, overwrite = true)
            }
            out.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    actual fun delete(target: String): Boolean {
        if (target.startsWith("content://")) {
            return try {
                NepentheApp.appContext.contentResolver.delete(Uri.parse(target), null, null) >= 0
            } catch (_: Exception) {
                false
            }
        }
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
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
